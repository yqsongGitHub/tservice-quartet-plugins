<h1 align="center">Plugins for Tservice</h1>
<div align="center">
Tservice is a tool service for reporting, statistics, converting or such tasks that are running less than 10 minutes and more than 1 minutes. 

The repo is hosting all plugins for tservice.
</div>

<div align="center">

[![License](https://img.shields.io/npm/l/package.json.svg?style=flat)](./LICENSE)

</div>

Three Category for Plugins: Tool, Report, Graph

## Table of contents

- [Tservice Plugins (RECOMMENDATION)](#tservice-plugins-recommendation)
  - [Architecture of Tservice Plugin System](#architecture-of-tservice-plugin-system)
  - [How to install plugins for tservice](#how-to-install-plugins-for-tservice)
    - [R Packages](#r-packages)
    - [Python Packages](#python-packages)
- [Tservice Plugins (NOT RECOMMENDATION)](#tservice-plugins-not-recommendation)
- [API Sepcification](#api-sepcification)
- [Contributors](#contributors)

## Tservice Plugins (RECOMMENDATION)

### Architecture of Tservice Plugin System

```
Executable entrypoint file --->|
Tservice Common Library    --->| --> wrapper --> plugin --> Tservice Loader --> Tservice API
Plugin Library file        --->|

├── external                        # All external program, such as python/R/bash/rust program.
│   ├── README.md
│   ├── bin                         # Executable entrypoint files which are related with plugins.
│   └── data                        # Dependent data files for executable entrypoint files
├── plugins                         # Definition file for plugin
│   ├── config                      # Config file for wrapper/plugin.
│   ├── docs                        # Documentation file for plugin.
│   ├── libs                        # Library for plugin or wrapper.
│   ├── wrappers                    # A wrapper for executable entrypoint file
│   ├── quartet_dnaseq_report.clj
│   ├── quartet_rnaseq_report.clj
│   ├── ballgown2exp.clj
│   └── xps2pdf.clj
├── poetry.lock                     # Dependencies for python program by poetry
├── pyproject.toml                  # Config file for poetry
├── renv                            # Dependencies for R program by renv
│   ├── .gitignore
│   ├── activate.R
│   ├── library
│   ├── settings.dcf
│   └── staging
└── renv.lock
```

### How to install plugins for tservice

#### R Packages

- Connect tservice container

```
docker exec -it XXX bash
```

- Change directory to /plugins

```
cd /plugins
```

- Activate renv and restore all installed packages

```
renv::restore()
```

#### Python Packages

- Connect tservice container

```
docker exec -it XXX bash
```

- Change directory to /plugins

```
cd /plugins
```

- Activate renv and restore all installed packages

```
poetry install
```

## Tservice Plugins (NOT RECOMMENDATION)

### Installation

- Install Conda and set channels (.condarc)

```
channels:
  - defaults
show_channel_urls: true
channel_alias: https://mirrors.tuna.tsinghua.edu.cn/anaconda
default_channels:
  - https://mirrors.tuna.tsinghua.edu.cn/anaconda/pkgs/main
  - https://mirrors.tuna.tsinghua.edu.cn/anaconda/pkgs/free
  - https://mirrors.tuna.tsinghua.edu.cn/anaconda/pkgs/r
  - https://mirrors.tuna.tsinghua.edu.cn/anaconda/cloud/conda-forge
  - https://mirrors.tuna.tsinghua.edu.cn/anaconda/cloud/bioconda
  - https://mirrors.tuna.tsinghua.edu.cn/anaconda/cloud/pytorch
```

- Set faster repo for pip (~/.pip/pip.conf)

```
[global]
# index-url = https://pypi.tuna.tsinghua.edu.cn/simple
index-url = https://pypi.douban.com/simple/
```

- Set faster repo for R (~/.Rprofile)

```
options(BioC_mirror="https://mirrors.tuna.tsinghua.edu.cn/bioconductor")
```

- Create an environment

```
cd external
conda create --prefix .env python=3
conda install r-base
```

- Install some base dependencies

```
# Bash
yum install zlib-devel

# R
install.packages(c("devtools", "BiocManager"))
```

### List of plugins

#### ballgown2exp

ballgown2exp plugin is based on `rnaseq2report.R` and `multireport`.

```
# Launch base environment
conda activate .env

# Enter R environment
R

# Install R packages by install.packages function in R environment for rnaseq2report.R
install.packages(c("gmodels", "tidyr"))

BiocManager::install("limma")
```

#### quartet-dnaseq-report

quartet-dnaseq-report plugin is based on [quartet-dnaseq-report](https://github.com/clinico-omics/quartet-dnaseq-report).

```
# Launch base environment
conda activate .env

# Install python package multireport
pip install MultiQC
pip install git+https://github.com/clinico-omics/quartet-dnaseq-report
```

#### quartet-rnaseq-report

quartet-rnaseq-report plugin is based on `multireport` and `exp2qcdt - Convert expression table to qc data table.`.

##### Installation

```
# Launch base environment
conda activate .env

# Install python package multireport
pip install MultiQC
pip install git+https://github.com/clinico-omics/quartet-rnaseq-report

# Install exp2qcdt packages by install.packages function in R environment for quartet-rnaseq-report
install.packages("data.table")
devtools::install_git("https://github.com/clinico-omics/exp2qcdt.git")
```

#### xps2pdf

Plugin xps2pdf is depend on `libgxps` tools, so need to install libgxps tools before using xps2pdf plugin.

##### Installation

> For Mac, `brew install libgxps`, more details in https://formulae.brew.sh/formula/libgxps
> For Linux, `yum install libgxps-tools`

```
# Launch base environment
conda activate .env

# Install libgxps
conda install libgxps
```

##### Usage

```bash
# Not need another bash wrapper
```

OR

```clojure
(require '[xps :refer [command]])

(command from to "pdf")
; or
(xps2pdf from to)
```

## API Sepcification

### Request

- filepath
- parameters
- metadata

### Response

- results
- log
- report
- id

## Contributors

- [Jingcheng Yang](https://github.com/yjcyxky)
- [Jun Shang](https://github.com/stead99)
- [Yaqing Liu](https://github.com/lyaqing)
