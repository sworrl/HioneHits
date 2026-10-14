package main

import (
	"embed"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"flag"
	"fmt"
	"log"
	"net"
	"net/http"
	"os/exec"
	"runtime"
	"sync"
	"time"
)

//go:embed web/*
var webFS embed.FS

//go:embed icons/hione-hits.png
var defaultIcon []byte

const (
	CastMagic       = "HION"
	DefaultCastPort = 8796
	DefaultWebPort  = 5050
	TypeFormat      = 1
	TypePcm         = 2
	TypeMeta        = 3
	TypeControl     = 4
	TypePing        = 5
)

type PlaybackStatus struct {
	Title      string `json:"title"`
	Artist     string `json:"artist"`
	Album      string `json:"album"`
	Format     string `json:"format"`
	DurationMs int    `json:"durationMs"`
	PositionMs int    `json:"positionMs"`
	IsPlaying  bool   `json:"isPlaying"`
	CastTarget string `json:"castTarget"`
	Bitrate    int    `json:"bitrate"`
}

type Engine struct {
	mu         sync.RWMutex
	status     PlaybackStatus
	castTarget string
	udpConn    *net.UDPConn
	tcpConn    net.Conn
	stopChan   chan struct{}
}

var engine = &Engine{
	status: PlaybackStatus{
		Title:      "Hione Hits Desktop",
		Artist:     "Bit-Perfect Audio Engine",
		Album:      "HioneOS Ecosystem",
		Format:     "PCM 44.1kHz / 16-bit",
		DurationMs: 225000,
		PositionMs: 0,
		IsPlaying:  false,
		CastTarget: "local",
		Bitrate:    1411,
	},
}

func main() {
	port := flag.Int("port", DefaultWebPort, "HTTP server port")
	castPort := flag.Int("cast-port", DefaultCastPort, "Hione Cast UDP/TCP port")
	target := flag.String("target", "", "Initial cast target (e.g. 10.3.64.222:5055)")
	noBrowser := flag.Bool("no-browser", false, "Do not launch browser UI automatically")
	flag.Parse()

	if *target != "" {
		engine.castTarget = *target
		engine.status.CastTarget = *target
	}

	// Start background Cast Receiver listener
	go startCastReceiver(*castPort)

	// Setup HTTP Handlers
	mux := http.NewServeMux()
	mux.HandleFunc("/api/status", handleStatus)
	mux.HandleFunc("/api/playback", handlePlayback)
	mux.HandleFunc("/api/cast", handleCast)
	mux.HandleFunc("/api/art", handleArt)
	mux.HandleFunc("/icon.png", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "image/png")
		w.Write(defaultIcon)
	})

	// Serve static web UI
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		path := r.URL.Path
		if path == "/" || path == "" {
			path = "web/index.html"
		} else {
			path = "web" + path
		}
		data, err := webFS.ReadFile(path)
		if err != nil {
			// Fallback to index.html for SPA routing
			data, err = webFS.ReadFile("web/index.html")
			if err != nil {
				http.NotFound(w, r)
				return
			}
		}
		if path == "web/index.html" {
			w.Header().Set("Content-Type", "text/html; charset=utf-8")
		}
		w.Write(data)
	})

	addr := fmt.Sprintf("127.0.0.1:%d", *port)
	url := fmt.Sprintf("http://%s", addr)

	fmt.Printf("====================================================\n")
	fmt.Printf(" Hione Hits Desktop - High-Fidelity Audio & Cast\n")
	fmt.Printf(" Web UI:       %s\n", url)
	fmt.Printf(" Cast Port:    %d (TCP/UDP)\n", *castPort)
	fmt.Printf(" System:       %s/%s\n", runtime.GOOS, runtime.GOARCH)
	fmt.Printf("====================================================\n")

	if !*noBrowser {
		go func() {
			time.Sleep(300 * time.Millisecond)
			launchBrowser(url)
		}()
	}

	server := &http.Server{
		Addr:    addr,
		Handler: mux,
	}

	log.Fatal(server.ListenAndServe())
}

func handleStatus(w http.ResponseWriter, r *http.Request) {
	engine.mu.RLock()
	defer engine.mu.RUnlock()
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(engine.status)
}

func handlePlayback(w http.ResponseWriter, r *http.Request) {
	cmd := r.URL.Query().Get("cmd")
	engine.mu.Lock()
	defer engine.mu.Unlock()

	switch cmd {
	case "play":
		engine.status.IsPlaying = true
		go engine.sendProgressToTarget()
	case "pause":
		engine.status.IsPlaying = false
		go engine.sendProgressToTarget()
	case "next":
		engine.status.PositionMs = 0
		engine.status.Title = "Hi-Res Audio Stream"
		engine.status.Artist = "Hione Hits Player"
		go engine.sendMetaToTarget()
	case "prev":
		engine.status.PositionMs = 0
		go engine.sendMetaToTarget()
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]any{"ok": true, "status": engine.status})
}

func handleCast(w http.ResponseWriter, r *http.Request) {
	target := r.URL.Query().Get("target")
	engine.mu.Lock()
	engine.castTarget = target
	engine.status.CastTarget = target
	engine.mu.Unlock()

	if target != "" && target != "local" {
		go engine.connectAndStreamTarget(target)
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]any{"ok": true, "target": target})
}

func handleArt(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "image/png")
	w.Write(defaultIcon)
}

func (e *Engine) sendMetaToTarget() {
	e.mu.RLock()
	target := e.castTarget
	status := e.status
	e.mu.RUnlock()

	if target == "" || target == "local" {
		return
	}

	conn, err := net.DialTimeout("tcp", target, 2*time.Second)
	if err != nil {
		log.Printf("[Cast] Failed to dial meta target %s: %v", target, err)
		return
	}
	defer conn.Close()

	// 1. Send Format Frame
	fmtPayload, _ := json.Marshal(map[string]any{
		"sampleRate":     48000,
		"bytesPerSample": 2,
		"channelCount":   2,
		"encoding":       2,
	})
	hdrFmt := make([]byte, 9)
	copy(hdrFmt[0:4], []byte(CastMagic))
	hdrFmt[4] = byte(TypeFormat)
	binary.BigEndian.PutUint32(hdrFmt[5:9], uint32(len(fmtPayload)))
	conn.Write(hdrFmt)
	conn.Write(fmtPayload)

	// 2. Send Meta Frame
	meta := map[string]any{
		"title":      status.Title,
		"artist":     status.Artist,
		"album":      status.Album,
		"durationMs": status.DurationMs,
		"positionMs": status.PositionMs,
		"playing":    status.IsPlaying,
		"artB64":     base64.StdEncoding.EncodeToString(defaultIcon),
	}
	payload, _ := json.Marshal(meta)

	hdr := make([]byte, 9)
	copy(hdr[0:4], []byte(CastMagic))
	hdr[4] = byte(TypeMeta)
	binary.BigEndian.PutUint32(hdr[5:9], uint32(len(payload)))

	conn.Write(hdr)
	conn.Write(payload)
}

func (e *Engine) sendProgressToTarget() {
	e.mu.RLock()
	target := e.castTarget
	status := e.status
	e.mu.RUnlock()

	if target == "" || target == "local" {
		return
	}

	conn, err := net.DialTimeout("tcp", target, 1*time.Second)
	if err != nil {
		return
	}
	defer conn.Close()

	meta := map[string]any{
		"title":      status.Title,
		"artist":     status.Artist,
		"album":      status.Album,
		"durationMs": status.DurationMs,
		"positionMs": status.PositionMs,
		"playing":    status.IsPlaying,
	}
	payload, _ := json.Marshal(meta)

	hdr := make([]byte, 9)
	copy(hdr[0:4], []byte(CastMagic))
	hdr[4] = byte(TypeMeta)
	binary.BigEndian.PutUint32(hdr[5:9], uint32(len(payload)))

	conn.Write(hdr)
	conn.Write(payload)
}

func (e *Engine) connectAndStreamTarget(target string) {
	log.Printf("[Cast] Establishing stream connection to %s...", target)
	e.sendMetaToTarget()
	e.sendProgressToTarget()
}

func startCastReceiver(port int) {
	addr := net.UDPAddr{
		Port: port,
		IP:   net.ParseIP("0.0.0.0"),
	}
	conn, err := net.ListenUDP("udp", &addr)
	if err != nil {
		log.Printf("[CastReceiver] UDP listen error: %v", err)
		return
	}
	defer conn.Close()

	buf := make([]byte, 8192)
	for {
		n, raddr, err := conn.ReadFromUDP(buf)
		if err != nil {
			continue
		}
		if n < 9 {
			continue
		}
		if string(buf[0:4]) != CastMagic {
			continue
		}
		pktType := buf[4]
		if pktType == TypePcm {
			// Received live audio chunk from M500 or sender
			_ = raddr
		}
	}
}

func launchBrowser(url string) {
	var cmd *exec.Cmd
	switch runtime.GOOS {
	case "linux":
		// Try chrome/chromium app mode first for native app feel
		if p, err := exec.LookPath("google-chrome"); err == nil {
			cmd = exec.Command(p, fmt.Sprintf("--app=%s", url))
		} else if p, err := exec.LookPath("chromium"); err == nil {
			cmd = exec.Command(p, fmt.Sprintf("--app=%s", url))
		} else {
			cmd = exec.Command("xdg-open", url)
		}
	case "darwin":
		cmd = exec.Command("open", url)
	case "windows":
		cmd = exec.Command("rundll32", "url.dll,FileProtocolHandler", url)
	}
	if cmd != nil {
		_ = cmd.Start()
	}
}
