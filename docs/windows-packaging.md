# Windows Packaging Guide

## Goal

Deliver a double-click executable that works without running commands.

---

# Option 1 – Single File Publish (Recommended)

## Build Self-Contained EXE
dotnet publish windows/PhoneMicReceiver.App
-c Release
-r win-x64
--self-contained true
/p:PublishSingleFile=true

Output:

Distribute:
- EXE file
- README
- Optional shortcut

---

# Option 2 – MSIX (Modern Installer)

Use Visual Studio:
- Add Windows Packaging Project
- Configure identity
- Generate MSIX package

Pros:
- Clean install/uninstall
- Trusted deployment
- Windows 11 native

---

# Option 3 – MSI (WiX Toolset)

For classic installer:
- Install WiX Toolset
- Define Product.wxs
- Build MSI

Recommended only if enterprise distribution needed.

---

# Run at Startup

Add registry key:
HKCU\Software\Microsoft\Windows\CurrentVersion\Run

Value:PhoneMicReceiver = "path_to_exe"

---

# Signing (Optional but Recommended)

Sign EXE with:
signtool sign /a /fd SHA256 yourfile.exe

Prevents Windows SmartScreen warnings.