# Source list shared by the Android build (CMakeLists.txt) and the host test (hosttest/).
set(MIKUBEAT_DIR ${CMAKE_CURRENT_LIST_DIR})
set(MIKUBEAT_SOURCES
        ${MIKUBEAT_DIR}/miku_beat_offline.cpp
        ${MIKUBEAT_DIR}/miku_beat_live.cpp
        ${MIKUBEAT_DIR}/shim/samplerate_shim.c
        ${MIKUBEAT_DIR}/btrack/BTrack.cpp
        ${MIKUBEAT_DIR}/btrack/OnsetDetectionFunction.cpp
        ${MIKUBEAT_DIR}/qm-dsp/dsp/onsets/DetectionFunction.cpp
        ${MIKUBEAT_DIR}/qm-dsp/dsp/phasevocoder/PhaseVocoder.cpp
        ${MIKUBEAT_DIR}/qm-dsp/dsp/transforms/FFT.cpp
        ${MIKUBEAT_DIR}/qm-dsp/dsp/tempotracking/TempoTrackV2.cpp
        ${MIKUBEAT_DIR}/qm-dsp/maths/MathUtilities.cpp
        ${MIKUBEAT_DIR}/qm-dsp/ext/kissfft/kiss_fft.c
        ${MIKUBEAT_DIR}/qm-dsp/ext/kissfft/tools/kiss_fftr.c)
set(MIKUBEAT_INCLUDES
        ${MIKUBEAT_DIR}
        ${MIKUBEAT_DIR}/shim
        ${MIKUBEAT_DIR}/btrack
        ${MIKUBEAT_DIR}/qm-dsp
        ${MIKUBEAT_DIR}/qm-dsp/ext/kissfft
        ${MIKUBEAT_DIR}/qm-dsp/ext/kissfft/tools)
# One kissfft build serves both QM-DSP and BTrack, in double precision as QM-DSP expects.
set(MIKUBEAT_DEFINES kiss_fft_scalar=double USE_KISS_FFT NDEBUG)
