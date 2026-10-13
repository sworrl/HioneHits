#!/usr/bin/env bash
set -e

VERSION="2.2.0"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DIST_DIR="${SCRIPT_DIR}/dist"
mkdir -p "${DIST_DIR}"

echo "==> Building Linux amd64 binary..."
GOOS=linux GOARCH=amd64 go build -ldflags="-s -w" -o "${SCRIPT_DIR}/hione-hits-linux-amd64" .

echo "==> Building macOS Apple Silicon (arm64) binary..."
GOOS=darwin GOARCH=arm64 go build -ldflags="-s -w" -o "${SCRIPT_DIR}/hione-hits-darwin-arm64" .

echo "==> Building macOS Intel (amd64) binary..."
GOOS=darwin GOARCH=amd64 go build -ldflags="-s -w" -o "${SCRIPT_DIR}/hione-hits-darwin-amd64" .

# ==========================================================
# 1. Debian Package (.deb)
# ==========================================================
echo "==> Creating Debian package..."
DEB_ROOT="${SCRIPT_DIR}/build/deb"
rm -rf "${DEB_ROOT}"
mkdir -p "${DEB_ROOT}/DEBIAN"
mkdir -p "${DEB_ROOT}/usr/bin"
mkdir -p "${DEB_ROOT}/usr/share/applications"
mkdir -p "${DEB_ROOT}/usr/share/icons/hicolor/192x192/apps"

cat <<EOF > "${DEB_ROOT}/DEBIAN/control"
Package: hione-hits
Version: ${VERSION}
Section: sound
Priority: optional
Architecture: amd64
Maintainer: FalconTechnix <dev@falcontechnix.com>
Description: Hione Hits - High-Fidelity Bit-Perfect Audio Player & Hione Cast Streaming
 Desktop player with real-time bidirectional streaming, ProjectM visualizer,
 and Cyberpunk network data conduit.
EOF

cat <<EOF > "${DEB_ROOT}/usr/share/applications/hione-hits.desktop"
[Desktop Entry]
Name=Hione Hits
Comment=High-Fidelity Audio Player & Hione Cast Streaming
Exec=/usr/bin/hione-hits
Icon=hione-hits
Terminal=false
Type=Application
Categories=AudioVideo;Audio;Player;Music;
StartupWMClass=hione-hits
EOF

cp "${SCRIPT_DIR}/hione-hits-linux-amd64" "${DEB_ROOT}/usr/bin/hione-hits"
chmod 755 "${DEB_ROOT}/usr/bin/hione-hits"
cp "${SCRIPT_DIR}/icons/hione-hits.png" "${DEB_ROOT}/usr/share/icons/hicolor/192x192/apps/hione-hits.png"

dpkg-deb --build --root-owner-group "${DEB_ROOT}" "${DIST_DIR}/hione-hits_${VERSION}_amd64.deb"
echo "  [OK] Created ${DIST_DIR}/hione-hits_${VERSION}_amd64.deb"

# ==========================================================
# 2. RPM Package (.rpm)
# ==========================================================
echo "==> Creating RPM package..."
RPM_TOP="${SCRIPT_DIR}/build/rpmbuild"
rm -rf "${RPM_TOP}"
mkdir -p "${RPM_TOP}"/{BUILD,RPMS,SOURCES,SPECS,SRPMS,BUILDROOT}

cat <<EOF > "${RPM_TOP}/SPECS/hione-hits.spec"
Name:           hione-hits
Version:        ${VERSION}
Release:        1%{?dist}
Summary:        Hione Hits High-Fidelity Audio Player & Cast Streaming
License:        Proprietary
URL:            https://github.com/sworrl/HioneHits

%description
High-Fidelity bit-perfect audio player with Hione Cast bidirectional streaming.

%install
mkdir -p %{buildroot}/usr/bin
mkdir -p %{buildroot}/usr/share/applications
mkdir -p %{buildroot}/usr/share/icons/hicolor/192x192/apps
install -m 755 ${SCRIPT_DIR}/hione-hits-linux-amd64 %{buildroot}/usr/bin/hione-hits
install -m 644 ${DEB_ROOT}/usr/share/applications/hione-hits.desktop %{buildroot}/usr/share/applications/hione-hits.desktop
install -m 644 ${SCRIPT_DIR}/icons/hione-hits.png %{buildroot}/usr/share/icons/hicolor/192x192/apps/hione-hits.png

%files
/usr/bin/hione-hits
/usr/share/applications/hione-hits.desktop
/usr/share/icons/hicolor/192x192/apps/hione-hits.png

%changelog
* Tue Oct 13 2026 FalconTechnix <dev@falcontechnix.com> - 2.2.0-1
- Release 2.2.0 with Hione Cast, Cyberpunk visualizer, and Cassette mode
EOF

rpmbuild --define "_topdir ${RPM_TOP}" -bb "${RPM_TOP}/SPECS/hione-hits.spec"
cp "${RPM_TOP}/RPMS/x86_64/"*.rpm "${DIST_DIR}/"
echo "  [OK] Created RPM in ${DIST_DIR}/"

# ==========================================================
# 3. macOS App Bundle (.app & .zip)
# ==========================================================
echo "==> Creating macOS App Bundle..."
MAC_APP="${SCRIPT_DIR}/build/HioneHits.app"
rm -rf "${MAC_APP}"
mkdir -p "${MAC_APP}/Contents/MacOS"
mkdir -p "${MAC_APP}/Contents/Resources"

cat <<EOF > "${MAC_APP}/Contents/Info.plist"
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleExecutable</key>
    <string>hione-hits</string>
    <key>CFBundleIconFile</key>
    <string>AppIcon</string>
    <key>CFBundleIdentifier</key>
    <string>com.hione.hits.desktop</string>
    <key>CFBundleName</key>
    <string>Hione Hits</string>
    <key>CFBundlePackageType</key>
    <string>APPL</string>
    <key>CFBundleShortVersionString</key>
    <string>${VERSION}</string>
    <key>LSMinimumSystemVersion</key>
    <string>11.0</string>
    <key>NSHighResolutionCapable</key>
    <true/>
</dict>
</plist>
EOF

# For Apple Silicon Mac mini, use arm64 binary
cp "${SCRIPT_DIR}/hione-hits-darwin-arm64" "${MAC_APP}/Contents/MacOS/hione-hits"
chmod 755 "${MAC_APP}/Contents/MacOS/hione-hits"
cp "${SCRIPT_DIR}/icons/hione-hits.png" "${MAC_APP}/Contents/Resources/AppIcon.png"

cd "${SCRIPT_DIR}/build"
zip -r "${DIST_DIR}/HioneHits-v${VERSION}-macos-arm64.zip" "HioneHits.app"
echo "  [OK] Created ${DIST_DIR}/HioneHits-v${VERSION}-macos-arm64.zip"

echo "==> All desktop packages built successfully:"
ls -lh "${DIST_DIR}"
