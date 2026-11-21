%global debug_package %{nil}
%global __brp_check_rpaths %{nil}

%if 0%{?_privlibdir:1}
%global __provides_exclude_from ^/usr/lib/camcraft/.*\.so.*$
%global __requires_exclude ^lib(avcodec|avutil|swscale|avformat)\.so.*$
%endif

# Capture invocation working directory during spec parsing for sensible defaults.
%global camcraft_pwd %(pwd)

# RPM Version cannot contain '-' (convert SemVer prerelease '-' to '~').
%global camcraft_version %{lua:
  local v = rpm.expand("%{?_version}")
  if v == "" then
    local f = io.open(rpm.expand("%{camcraft_pwd}") .. "/desktop/Cargo.toml", "r")
    if f then
      for line in f:lines() do
        local m = line:match('^version%s*=%s*"([^"]+)"')
        if m then
          v = m
          break
        end
      end
      f:close()
    end
  end
  if v == "" then
    v = "0.0.1"
  end
  v = v:gsub("^[vV]", ""):gsub("-", "~")
  print(v)
}

# Note: rpmbuild reserves %%{_bindir} as /usr/bin by default; when --define "_bindir /path/to/release"
# is passed, read binaries from it while still installing into literal /usr/bin below.
%global camcraft_bindir %{lua:
  local b = rpm.expand("%{_bindir}")
  if b ~= "/usr/bin" and b ~= "" then
    print(b)
  else
    print(rpm.expand("%{camcraft_pwd}") .. "/desktop/target/release")
  end
}

# Resolve repository root across --define "_sourcedir ...", invocation pwd, or relative to _bindir.
%global camcraft_srcdir %{lua:
  local candidates = {
    rpm.expand("%{_sourcedir}"),
    rpm.expand("%{camcraft_pwd}"),
    rpm.expand("%{camcraft_bindir}") .. "/../../..",
  }
  local chosen = candidates[1]
  for _, dir in ipairs(candidates) do
    local f = io.open(dir .. "/LICENSE-MIT", "r")
    if f then
      f:close()
      chosen = dir
      break
    end
  end
  print(chosen)
}

Name:           camcraft
Version:        %{camcraft_version}
Release:        1
Summary:        Low-latency Android webcam and virtual microphone receiver for Linux

License:        MIT OR Apache-2.0
URL:            https://github.com/madhasoeki/CamCraft

# Let RPM auto-detect ELF shared library dependencies (libavcodec.so.*, libavutil.so.*,
# libswscale.so.*, etc.) so both stock Fedora ffmpeg-free and RPM Fusion ffmpeg-libs satisfy them.
Requires:       (pipewire-utils or pulseaudio-utils)
Recommends:     pipewire-pulseaudio
Suggests:       v4l2loopback

%description
CamCraft turns an Android smartphone into a local, ultra-low-latency
1080p-4K 60fps virtual webcam and virtual microphone on Linux over USB
(ADB / AOA) or Wi-Fi LAN.

%prep
# Stage license and doc files in the RPM build directory for %%license and %%doc.
cp -f "%{camcraft_srcdir}/LICENSE-MIT" "%{camcraft_srcdir}/LICENSE-APACHE" "%{camcraft_srcdir}/README.md" .

%build
# Pre-built release binaries are supplied via %%{_bindir} (or desktop/target/release).

%install
rm -rf "%{buildroot}"

install -Dm755 "%{camcraft_bindir}/camcraft-gui" "%{buildroot}/usr/bin/camcraft-gui"
install -Dm755 "%{camcraft_bindir}/camcraft-cli" "%{buildroot}/usr/bin/camcraft-cli"

%if 0%{?_privlibdir:1}
install -d "%{buildroot}/usr/lib/camcraft"
for f in "%{_privlibdir}"/lib*.so*; do
    install -Dm755 "$f" "%{buildroot}/usr/lib/camcraft/$(basename "$f")"
done
%endif

install -Dm644 "%{camcraft_srcdir}/assets/branding/linux/org.artcraft.camcraft.desktop" \
    "%{buildroot}/usr/share/applications/org.artcraft.camcraft.desktop"
install -Dm644 "%{camcraft_srcdir}/assets/branding/svg/camcraft-symbol-app-icon.svg" \
    "%{buildroot}/usr/share/icons/hicolor/scalable/apps/org.artcraft.camcraft.svg"
install -Dm644 "%{camcraft_srcdir}/assets/branding/png/camcraft-app-icon-256.png" \
    "%{buildroot}/usr/share/icons/hicolor/256x256/apps/org.artcraft.camcraft.png"
install -Dm644 "%{camcraft_srcdir}/packaging/linux/70-camcraft-aoa.rules" \
    "%{buildroot}/usr/lib/udev/rules.d/70-camcraft-aoa.rules"

%post
udevadm control --reload-rules >/dev/null 2>&1 || :
udevadm trigger --subsystem-match=usb >/dev/null 2>&1 || :
update-desktop-database -q /usr/share/applications >/dev/null 2>&1 || :
gtk-update-icon-cache -q -t -f /usr/share/icons/hicolor >/dev/null 2>&1 || :

%postun
udevadm control --reload-rules >/dev/null 2>&1 || :
update-desktop-database -q /usr/share/applications >/dev/null 2>&1 || :
gtk-update-icon-cache -q -t -f /usr/share/icons/hicolor >/dev/null 2>&1 || :

%files
%license LICENSE-MIT LICENSE-APACHE
%doc README.md
%attr(0755,root,root) /usr/bin/camcraft-gui
%attr(0755,root,root) /usr/bin/camcraft-cli
%if 0%{?_privlibdir:1}
%dir /usr/lib/camcraft
%attr(0755,root,root) /usr/lib/camcraft/lib*.so*
%endif
%attr(0644,root,root) /usr/share/applications/org.artcraft.camcraft.desktop
%attr(0644,root,root) /usr/share/icons/hicolor/scalable/apps/org.artcraft.camcraft.svg
%attr(0644,root,root) /usr/share/icons/hicolor/256x256/apps/org.artcraft.camcraft.png
%attr(0644,root,root) /usr/lib/udev/rules.d/70-camcraft-aoa.rules

%changelog
* Sat Nov 21 2026 ArtCraft <maintainers@artcraft.org> - 0.0.1-1
- Initial CamCraft RPM package with udev AOA rules and desktop integration
