---
title: Build from source code on AlmaLinux
headerTitle: Build the source code
linkTitle: Build the source
description: Build YugabyteDB from source code on AlmaLinux.
image: /images/section_icons/index/quick_start.png
headcontent: Build the source code.
aliases:
  - /preview/contribute/core-database/build-from-src
menu:
  preview:
    identifier: build-from-src-1-almalinux
    parent: core-database
    weight: 2912
type: docs
---

<ul class="nav nav-tabs-alt nav-tabs-yb">

  <li >
    <a href="{{< relref "./build-from-src-almalinux.md" >}}" class="nav-link active">
      <i class="fa-brands fa-linux" aria-hidden="true"></i>
      AlmaLinux
    </a>
  </li>

  <li >
    <a href="{{< relref "./build-from-src-macos.md" >}}" class="nav-link">
      <i class="fa-brands fa-apple" aria-hidden="true"></i>
      macOS
    </a>
  </li>

  <li >
    <a href="{{< relref "./build-from-src-centos.md" >}}" class="nav-link">
      <i class="fa-brands fa-linux" aria-hidden="true"></i>
      CentOS
    </a>
  </li>

  <li >
    <a href="{{< relref "./build-from-src-ubuntu.md" >}}" class="nav-link">
      <i class="fa-brands fa-linux" aria-hidden="true"></i>
      Ubuntu
    </a>
  </li>

</ul>

{{< note title="Note" >}}

AlmaLinux 8 is the recommended Linux development platform for YugabyteDB.

{{< /note >}}

## Install necessary packages

Update packages on your system, install development tools and additional packages:

```sh
sudo yum update -y
sudo yum groupinstall -y 'Development Tools'
sudo yum -y install epel-release libatomic rsync
```

### Python 3

{{% readfile "includes/python.md" %}}

The following example installs Python 3.9.

```sh
sudo yum install -y python39
```

In case there is more than one Python 3 version installed, ensure that `python3` refers to the right one.

```sh
sudo alternatives --set python3 /usr/bin/python3.9
sudo alternatives --display python3
python3 -V
```

### CMake 3

{{% readfile "includes/cmake.md" %}}

```sh
sudo yum install -y cmake3
```

### /opt/yb-build

{{% readfile "includes/opt-yb-build.md" %}}

### Ninja (optional)

Use [Ninja][ninja] for faster builds.

The latest release can be downloaded:

```sh
latest_zip_url=$(curl -Ls "https://api.github.com/repos/ninja-build/ninja/releases/latest" \
                 | grep browser_download_url | grep ninja-linux.zip | cut -d \" -f 4)
curl -Ls "$latest_zip_url" | zcat | sudo tee /usr/local/bin/ninja >/dev/null
sudo chmod +x /usr/local/bin/ninja
```

[ninja]: https://ninja-build.org

### Ccache (optional)

Use [Ccache][ccache] for faster builds.

```sh
sudo yum install -y ccache
# Also add the following line to your .bashrc or equivalent.
export YB_CCACHE_DIR="$HOME/.cache/yb_ccache"
```

[ccache]: https://ccache.dev

### GCC (optional)

To compile with GCC, install the following packages, and adjust the version numbers to match the GCC version you plan to use.

```sh
sudo yum install -y gcc-toolset-11 gcc-toolset-11-libatomic-devel
```

### Java

{{% readfile "includes/java.md" %}}

Both requirements can be satisfied by the package manager.

```sh
sudo yum install -y java-1.8.0-openjdk maven
```

## Build the code

{{% readfile "includes/build-the-code.md" %}}

### Build release package (optional)

Install the following additional packages to build yugabyted-ui:

```sh
sudo yum install -y npm golang
```

Run the `yb_release` script to build a release package:

```output.sh
$ ./yb_release
......
2023-02-14 04:14:16,092 [yb_release.py:299 INFO] Generated a package at '/home/user/code/yugabyte-db/build/yugabyte-2.17.2.0-b8e42eecde0e45a743d51e244dbd9662a6130cd6-release-clang15-centos-x86_64.tar.gz'
```
