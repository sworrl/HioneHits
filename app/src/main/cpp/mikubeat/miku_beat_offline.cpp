/*
 * MikuOS whole-track tempo (QM-DSP TempoTrackV2). See miku_beat.h.
 *
 * Copyright (C) 2026 sworrl. Part of MikuOS.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See
 * the GNU General Public License for more details.
 *
 * The offline settings (complex spectral difference, 512 step, 1024 window, drop the first two
 * detection-function values) follow the QM Vamp beat tracker and Mixxx's
 * AnalyzerQueenMaryBeats, which both drive TempoTrackV2 this way.
 */
#include "miku_beat.h"

#include <algorithm>
#include <cmath>
#include <numeric>
#ifdef MIKUBEAT_HOST_DEBUG
#include <cstdio>
#endif

#include "dsp/onsets/DetectionFunction.h"
#include "dsp/tempotracking/TempoTrackV2.h"

namespace mikubeat {

namespace {
constexpr int kZero = 8;          // zero crossings each side
constexpr int kTableRes = 512;    // table points per zero crossing

/**
 * Offset from a tracked beat to the onset it marks, measured with the synthetic drum test
 * (hosttest/bpm_eval.py, "phase_ms"): grid beats came out 12.5 ms late at every tempo and at
 * 44.1, 48 and 96 kHz. Detection-function value i is a window centred on i * kHop, and the
 * complex-SD rise lands about one hop after the hit.
 */
constexpr double kOfflinePhaseSec = -0.0125;

double median(std::vector<double> v) {
    if (v.empty()) return 0.0;
    size_t m = v.size() / 2;
    std::nth_element(v.begin(), v.begin() + m, v.end());
    double hi = v[m];
    if (v.size() % 2 == 1) return hi;
    double lo = *std::max_element(v.begin(), v.begin() + m);
    return 0.5 * (lo + hi);
}
}  // namespace

// ------------------------------------------------------------------------------------ Resampler

void Resampler::reset(double inRate, double outRate) {
    ratio_ = outRate / inRate;
    step_ = inRate / outRate;
    bypass_ = std::fabs(ratio_ - 1.0) < 1e-9;
    fc_ = std::min(1.0, ratio_);
    half_ = kZero / fc_;
    buf_.clear();
    t_ = 0.0;
    if (table_.empty()) {
        table_.resize(kZero * kTableRes + 2);
        for (size_t i = 0; i < table_.size(); i++) {
            double u = double(i) / kTableRes;  // in zero-crossing units
            double s = u < 1e-9 ? 1.0 : std::sin(M_PI * u) / (M_PI * u);
            double w = u >= kZero ? 0.0
                                  : 0.42 + 0.5 * std::cos(M_PI * u / kZero) + 0.08 * std::cos(2.0 * M_PI * u / kZero);
            table_[i] = float(s * w);
        }
    }
    // Pre-roll: the first output is centred on input sample 0, which needs half_ samples before it.
    if (!bypass_) {
        buf_.assign(size_t(std::ceil(half_)) + 1, 0.0f);
        t_ = std::ceil(half_) + 1;
    }
}

float Resampler::kernel(double x) const {
    double u = std::fabs(x) * fc_ * kTableRes;
    size_t i = size_t(u);
    if (i + 1 >= table_.size()) return 0.0f;
    double f = u - double(i);
    return float(table_[i] + f * (table_[i + 1] - table_[i]));
}

void Resampler::process(const float* in, size_t n, std::vector<float>& out) {
    if (bypass_) {
        out.insert(out.end(), in, in + n);
        return;
    }
    buf_.insert(buf_.end(), in, in + n);
    const double size = double(buf_.size());
    while (t_ + half_ + 1.0 < size) {
        long i0 = long(std::ceil(t_ - half_));
        long i1 = long(std::floor(t_ + half_));
        double acc = 0.0, wsum = 0.0;
        for (long i = i0; i <= i1; i++) {
            float h = kernel(t_ - double(i));
            acc += double(h) * buf_[size_t(i)];
            wsum += h;
        }
        out.push_back(float(wsum > 1e-9 ? acc / wsum : 0.0));
        t_ += step_;
    }
    // Keep only the history the next output still needs.
    long drop = long(std::floor(t_ - half_)) - 1;
    if (drop > 0) {
        buf_.erase(buf_.begin(), buf_.begin() + drop);
        t_ -= double(drop);
    }
}

void Resampler::flush(std::vector<float>& out) {
    if (bypass_) return;
    std::vector<float> zeros(size_t(std::ceil(half_)) + 2, 0.0f);
    process(zeros.data(), zeros.size(), out);
}

// ---------------------------------------------------------------------------------- folding / fit

double foldBpm(double bpm, double lo, double hi) {
    if (!(bpm > 0.0) || !std::isfinite(bpm)) return 0.0;
    while (bpm >= hi) bpm *= 0.5;
    while (bpm < lo) bpm *= 2.0;
    return bpm;
}

OfflineResult fitConstantGrid(const std::vector<double>& beats, double analysedSec) {
    OfflineResult r;
    r.analysedSec = analysedSec;
    r.beatCount = int(beats.size());
    r.beatsSec = beats;
    if (beats.size() < 8) return r;

    std::vector<double> ibi;
    ibi.reserve(beats.size());
    for (size_t i = 1; i < beats.size(); i++) ibi.push_back(beats[i] - beats[i - 1]);
    double p0 = median(ibi);
    if (!(p0 > 0.05)) return r;

    // Beat numbers: count whole periods between consecutive beats so a skipped or doubled beat
    // does not shift the rest of the numbering.
    std::vector<double> n(beats.size(), 0.0);
    for (size_t i = 1; i < beats.size(); i++) {
        double k = std::round(ibi[i - 1] / p0);
        if (k < 1.0) k = 1.0;
        n[i] = n[i - 1] + k;
    }

    // Least squares b = phi + P n, twice: the second pass drops beats more than 15 % of a period
    // off the first line (fills, a held break, a tracker slip).
    double P = p0, phi = beats[0];
    std::vector<char> use(beats.size(), 1);
    for (int pass = 0; pass < 3; pass++) {
        double sn = 0, sb = 0, snn = 0, snb = 0, cnt = 0;
        for (size_t i = 0; i < beats.size(); i++) {
            if (!use[i]) continue;
            sn += n[i]; sb += beats[i]; snn += n[i] * n[i]; snb += n[i] * beats[i]; cnt += 1;
        }
        double den = cnt * snn - sn * sn;
        if (cnt < 4 || std::fabs(den) < 1e-12) break;
        P = (cnt * snb - sn * sb) / den;
        phi = (sb - P * sn) / cnt;
        if (pass == 2) break;
        for (size_t i = 0; i < beats.size(); i++) {
            double res = beats[i] - (phi + P * n[i]);
            use[i] = std::fabs(res) < 0.15 * P;
        }
    }
    if (!(P > 0.05)) return r;

    // Residual spread of the inliers and the standard error of P.
    double ss = 0, cnt = 0, nm = 0;
    for (size_t i = 0; i < beats.size(); i++) if (use[i]) { nm += n[i]; cnt += 1; }
    nm = cnt > 0 ? nm / cnt : 0;
    double snn = 0;
    for (size_t i = 0; i < beats.size(); i++) {
        if (!use[i]) continue;
        double res = beats[i] - (phi + P * n[i]);
        ss += res * res;
        snn += (n[i] - nm) * (n[i] - nm);
    }
    double rms = cnt > 2 ? std::sqrt(ss / (cnt - 2)) : P;
    double seP = (cnt > 2 && snn > 0) ? rms / std::sqrt(snn) : P;

    double raw = 60.0 / P;
    double seBpm = 60.0 * seP / (P * P);
    // Most produced music sits on a whole-number tempo. Snap when the fit cannot tell the
    // difference anyway, then refit the phase for the snapped period.
    double snapped = std::round(raw);
    if (std::fabs(raw - snapped) <= std::max(0.05, 2.0 * seBpm)) {
        raw = snapped;
        P = 60.0 / raw;
        double acc = 0;
        for (size_t i = 0; i < beats.size(); i++) if (use[i]) acc += beats[i] - P * n[i];
        phi = cnt > 0 ? acc / cnt : phi;
    }

    // Regularity: share of inter-beat intervals within 5 % of the fitted period.
    int regular = 0;
    for (double d : ibi) if (std::fabs(d - P) <= 0.05 * P) regular++;
    double c1 = double(regular) / double(ibi.size());
    // Grid fit: a steady track stays within a few percent of one period across its length.
    double rel = rms / P;
    double c2 = std::exp(-(rel / 0.08) * (rel / 0.08));
    double inlierShare = cnt / double(beats.size());
    r.confidence = std::max(0.0, std::min(1.0, c1 * (0.6 + 0.4 * c2) * std::min(1.0, inlierShare / 0.8)));

    r.rawBpm = raw;
    r.bpm = foldBpm(raw);
    double Pf = 60.0 / r.bpm;
    double first = std::fmod(phi, Pf);
    if (first < 0) first += Pf;
    r.firstBeatSec = first;
    r.ok = true;
    return r;
}

// ------------------------------------------------------------------------------ OfflineAnalyzer

OfflineAnalyzer::OfflineAnalyzer(int srcRate, int channels)
    : channels_(std::max(1, channels)), srcRate_(srcRate) {
    rs_.reset(double(srcRate), double(kRate));
    DFConfig c;
    c.stepSize = kHop;
    c.frameLength = kWin;
    c.DFType = DF_COMPLEXSD;
    c.dbRise = 3;
    c.adaptiveWhitening = false;
    c.whiteningRelaxCoeff = -1;
    c.whiteningFloor = -1;
    df_.reset(new DetectionFunction(c));
    frame_.resize(kWin);
    // Centre the first analysis window on sample 0, so detection-function value i is the window
    // centred on i * kHop.
    pending_.assign(kWin / 2, 0.0f);
}

OfflineAnalyzer::~OfflineAnalyzer() = default;

void OfflineAnalyzer::feedInterleaved(const float* x, int frames) {
    if (frames <= 0) return;
    mono_.resize(size_t(frames));
    const float g = 1.0f / float(channels_);
    for (int f = 0; f < frames; f++) {
        float s = 0;
        for (int c = 0; c < channels_; c++) s += x[size_t(f) * channels_ + c];
        mono_[size_t(f)] = s * g;
    }
    samplesIn_ += frames;
    pushMono(mono_.data(), mono_.size());
}

void OfflineAnalyzer::pushMono(const float* x, size_t n) {
    resampled_.clear();
    rs_.process(x, n, resampled_);
    pending_.insert(pending_.end(), resampled_.begin(), resampled_.end());
    drainFrames(false);
}

void OfflineAnalyzer::drainFrames(bool final) {
    while (pending_.size() - pendingPos_ >= size_t(kWin)) {
        for (int i = 0; i < kWin; i++) frame_[size_t(i)] = pending_[pendingPos_ + size_t(i)];
        dfOut_.push_back(df_->processTimeDomain(frame_.data()));
        pendingPos_ += kHop;
    }
    if (final && pending_.size() > pendingPos_) {
        size_t left = pending_.size() - pendingPos_;
        if (left > size_t(kHop)) {
            std::fill(frame_.begin(), frame_.end(), 0.0);
            for (size_t i = 0; i < left && i < size_t(kWin); i++) frame_[i] = pending_[pendingPos_ + i];
            dfOut_.push_back(df_->processTimeDomain(frame_.data()));
        }
    }
    if (pendingPos_ > (1u << 16)) {
        pending_.erase(pending_.begin(), pending_.begin() + long(pendingPos_));
        pendingPos_ = 0;
    }
}

OfflineResult OfflineAnalyzer::finish() {
    resampled_.clear();
    rs_.flush(resampled_);
    pending_.insert(pending_.end(), resampled_.begin(), resampled_.end());
    drainFrames(true);

    double analysedSec = srcRate_ > 0 ? double(samplesIn_) / double(srcRate_) : 0.0;

    int nonZero = int(dfOut_.size());
    while (nonZero > 0 && dfOut_[size_t(nonZero - 1)] <= 0.0) --nonZero;
    // As the Vamp plugin and Mixxx do: drop the first two values, which can read the start of
    // the signal itself as an onset.
    constexpr int kSkip = 2;
    if (nonZero - kSkip < 64) {
        OfflineResult r;
        r.analysedSec = analysedSec;
        return r;
    }
    std::vector<double> df(dfOut_.begin() + kSkip, dfOut_.begin() + nonZero);
    std::vector<double> beatPeriod(df.size(), 0.0);
    std::vector<double> tempi;
    TempoTrackV2 tt(float(kRate), kHop);
    tt.calculateBeatPeriod(df, beatPeriod, tempi);
    std::vector<double> beats;
    tt.calculateBeats(df, beatPeriod, beats);

    std::vector<double> beatsSec;
    beatsSec.reserve(beats.size());
    for (double b : beats) beatsSec.push_back((b + kSkip) * kHop / double(kRate) + kOfflinePhaseSec);

    OfflineResult r = fitConstantGrid(beatsSec, analysedSec);
    std::vector<double> tp;
    tp.reserve(tempi.size());
    for (double t : tempi) if (t > 0) tp.push_back(t);
    r.medianTempoCurve = median(tp);
    if (r.ok) {
        r.bpm = resolveOctave(df, r.bpm, &r.octaveNote);
        // Doubling keeps every old grid beat on the new grid; halving keeps one of each pair.
        r.firstBeatSec = std::fmod(r.firstBeatSec, 60.0 / r.bpm);
    }
    return r;
}

/**
 * Second opinion on the octave. TempoTrackV2's tempo prior is centred near 120 BPM, so a fast
 * track with a strong pulse on every beat (174 D&B, 180 punk) tends to come out at half speed.
 * Folding into 70..180 cannot fix that, because the half tempo is inside the range too.
 *
 * The check, after the 0.3.0 analyzer's best idea: how busy the onsets are sets where the
 * perceived pulse sits (dense onsets are heard as a faster beat), and the detection function's
 * autocorrelation says whether the other octave is a real periodicity at all. The other octave
 * replaces the tracked one only when it is at least half as periodic and clearly closer to that
 * centre. Applied in both directions, within 70..180.
 */
/** The other octave must be at least this periodic, relative to the tracked one, to be chosen. */
constexpr double kOctaveMinAcf = 0.5;

double OfflineAnalyzer::resolveOctave(const std::vector<double>& df, double bpm, int* note) {
    *note = 0;
    const double rate = double(kRate) / kHop;  // detection-function frames per second
    const size_t n = df.size();
    if (n < size_t(rate * 20) || bpm <= 0) return bpm;

    // Onset density: local maxima above 30 % of the +-1 s local maximum, at least 60 ms apart.
    const int half = int(rate);
    const int minGap = std::max(2, int(0.06 * rate));
    int onsets = 0, last = -minGap;
    for (int i = 1; i + 1 < int(n); i++) {
        double v = df[size_t(i)];
        if (v <= 0 || v < df[size_t(i - 1)] || v < df[size_t(i + 1)]) continue;
        double lm = 0;
        for (int k = std::max(0, i - half); k < std::min(int(n), i + half + 1); k++) lm = std::max(lm, df[size_t(k)]);
        if (v < 0.3 * lm || i - last < minGap) continue;
        onsets++;
        last = i;
    }
    const double density = onsets / (double(n) / rate);
    const double centre = std::min(185.0, std::max(110.0, 95.0 + 17.0 * density));

    // Autocorrelation of the mean-removed detection function near a tempo's lag.
    double mean = 0;
    for (double v : df) mean += v;
    mean /= double(n);
    auto acf = [&](double tempo) {
        double lag = 60.0 * rate / tempo;
        int l0 = int(std::floor(lag * 0.97)), l1 = int(std::ceil(lag * 1.03));
        double best = 0;
        for (int l = std::max(1, l0); l <= l1 && l < int(n) / 2; l++) {
            double acc = 0;
            for (size_t i = 0; i + size_t(l) < n; i++) acc += (df[i] - mean) * (df[i + size_t(l)] - mean);
            best = std::max(best, acc / double(n - size_t(l)));
        }
        return best;
    };
    auto weight = [&](double tempo) {
        double o = std::log2(tempo / centre) / 0.5;
        return std::exp(-0.5 * o * o);
    };
    const double base = acf(bpm);
    if (base <= 0) return bpm;
    double chosen = bpm;
    double bestScore = base * weight(bpm);
    for (double alt : {bpm * 2.0, bpm * 0.5}) {
        if (alt < kFoldMinBpm || alt >= kFoldMaxBpm) continue;
        double a = acf(alt);
        if (a < kOctaveMinAcf * base) continue;
        double sc = a * weight(alt);
        if (sc > 1.15 * bestScore) { bestScore = sc; chosen = alt; }
    }
#ifdef MIKUBEAT_HOST_DEBUG
    std::fprintf(stderr, "octave: bpm=%.2f density=%.2f centre=%.1f acf(b)=%.4g acf(2b)=%.4g acf(b/2)=%.4g\n",
                 bpm, density, centre, base, acf(bpm * 2), acf(bpm / 2));
#endif
    if (chosen != bpm) *note = chosen > bpm ? 2 : -2;
    return chosen;
}

}  // namespace mikubeat
