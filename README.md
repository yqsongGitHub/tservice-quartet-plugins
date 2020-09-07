# Tservice Plugins (Reproducible Way)
## Installation
### R Packages
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

### Python Packages
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

# Tservice Plugins (Obsolete)
## Installation
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

## List of plugins
### ballgown2exp
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

### quartet-dnaseq-report
quartet-dnaseq-report plugin is based on `[quartet-dnaseq-report](https://github.com/clinico-omics/quartet-dnaseq-report)`.

```
# Launch base environment
conda activate .env

# Install python package multireport
pip install MultiQC
pip install git+https://github.com/clinico-omics/quartet-dnaseq-report
```

### quartet-rnaseq-report
quartet-rnaseq-report plugin is based on `multireport` and `exp2qcdt - Convert expression table to qc data table.`.

#### Installation

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

### xps2pdf
Plugin xps2pdf is depend on `libgxps` tools, so need to install libgxps tools before using xps2pdf plugin.

#### Installation

> For Mac, `brew install libgxps`, more details in https://formulae.brew.sh/formula/libgxps
> For Linux, `yum install libgxps-tools`

```
# Launch base environment
conda activate .env

# Install libgxps
conda install libgxps
```

#### Usage

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