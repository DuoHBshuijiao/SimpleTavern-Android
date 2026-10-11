# Bundled sandbox inputs

Local development bundle, 2026-10-06. No hashes or device execution were used.

| Component | Version | Original binary/source |
| --- | --- | --- |
| PRoot | Termux 5.1.107.96, AArch64 | https://packages.termux.dev/apt/termux-main/pool/main/p/proot/proot_5.1.107.96_aarch64.deb ; https://github.com/termux/proot/tree/v5.1.107.96 |
| libtalloc | Termux 2.5.0, AArch64 | https://packages.termux.dev/apt/termux-main/pool/main/libt/libtalloc/libtalloc_2.5.0_aarch64.deb ; https://www.samba.org/ftp/talloc/talloc-2.5.0.tar.gz |
| libandroid-shmem | Termux 0.7, AArch64 | https://packages.termux.dev/apt/termux-main/pool/main/liba/libandroid-shmem/libandroid-shmem_0.7_aarch64.deb ; https://github.com/termux/libandroid-shmem/tree/v0.7 |
| Alpine minirootfs | 3.24.2, AArch64 | https://dl-cdn.alpinelinux.org/alpine/v3.24/releases/aarch64/alpine-minirootfs-3.24.2-aarch64.tar.gz ; downloaded through https://mirrors.tuna.tsinghua.edu.cn/alpine/v3.24/releases/aarch64/alpine-minirootfs-3.24.2-aarch64.tar.gz |

Original three Debian packages and upstream source archives are retained here. The complete minirootfs is in `runtime/sandbox/src/main/assets/sandbox/alpine-arm64.tar.gz`. Interrupted CDN/index downloads are not bundle inputs.

`tools/PrepareSandboxRuntime.ps1` extracts only the needed regular files. PRoot's DT_NEEDED and talloc's SONAME are shortened in place from `libtalloc.so.2` to `libtalloc.so`, zero-padding the original slot without changing offsets. Executables are named `libstproot.so` / `libstprootloader.so` so Android can extract them to its executable native-library directory. There is no LD_PRELOAD workaround or execution from app-writable files.

Upstream notices are copied into APK assets: PRoot COPYING (GPL v2 or later), libandroid-shmem LICENSE (BSD-3-Clause), and talloc LICENSE (LGPL v3). Termux's talloc package declares GPL-3.0; upstream archive/license alone is not a claim that all package/release obligations are satisfied. Before external distribution, collect the exact Termux build recipes/patches and corresponding source/notices for all bundled Alpine packages, and review the release obligations. This change does not publish the APK or claim a completed distribution-source audit.

Build recipe references: https://github.com/termux/termux-packages/tree/master/packages/proot , https://github.com/termux/termux-packages/tree/master/packages/libtalloc , https://github.com/termux/termux-packages/tree/master/packages/libandroid-shmem . These mutable references are provenance pointers, not pinned reproduction inputs.
