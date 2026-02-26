# Windows Packaging Guide

This document covers Task **1.7.8 Packaging** for the WPF receiver app.

## Publish profiles

The receiver app includes three publish profiles under:

- `windows/PhoneMicReceiver.App/Properties/PublishProfiles/Portable.pubxml`
- `windows/PhoneMicReceiver.App/Properties/PublishProfiles/SingleFile.pubxml`
- `windows/PhoneMicReceiver.App/Properties/PublishProfiles/SelfContainedSingleFile.pubxml`

Run these commands from repository root.

### 1) Portable build (framework-dependent)

```powershell
dotnet publish windows/PhoneMicReceiver.App/PhoneMicReceiver.App.csproj -p:PublishProfile=Portable
```

Output folder:

- `windows/PhoneMicReceiver.App/bin/Release/net8.0-windows/publish/portable/`

Use this when target machines already have the .NET Desktop Runtime installed.

### 2) Single-file build (framework-dependent)

```powershell
dotnet publish windows/PhoneMicReceiver.App/PhoneMicReceiver.App.csproj -p:PublishProfile=SingleFile
```

Output folder:

- `windows/PhoneMicReceiver.App/bin/Release/net8.0-windows/publish/single-file/`

Produces a single EXE while still depending on the installed .NET runtime.

### 3) Self-contained single-file build (optional)

```powershell
dotnet publish windows/PhoneMicReceiver.App/PhoneMicReceiver.App.csproj -p:PublishProfile=SelfContainedSingleFile
```

Output folder:

- `windows/PhoneMicReceiver.App/bin/Release/net8.0-windows/publish/self-contained/`

This is the best option for fresh Windows machines with no .NET runtime preinstalled.

## Optional installer

No MSI/MSIX project is included in MVP. If you need an installer later:

- **MSIX**: use a Visual Studio Windows Packaging Project.
- **MSI**: use WiX Toolset for classic enterprise deployment.

## Versioning

- App version is set in `PhoneMicReceiver.App.csproj` (`Version`, `AssemblyVersion`, `FileVersion`, `InformationalVersion`).
- The GUI displays the version in the main window footer.
- Update `CHANGELOG.md` for every release.
