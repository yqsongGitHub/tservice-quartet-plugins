## Requirements

- optparse
- SigMA

## R环境下安装SigMa
wget http://nordata-cdn.oss-cn-shanghai.aliyuncs.com/BSgenome.Hsapiens.UCSC.hg19_1.4.3.tar.gz
wget http://nordata-cdn.oss-cn-shanghai.aliyuncs.com/SigMA_1.0.0.0.tar.gz

```
BiocManager::install("BSgenome")
install.packages("BSgenome.Hsapiens.UCSC.hg19_1.4.3.tar.gz", repos=NULL)
renv::restore()

install.packages("SigMA_1.0.0.0.tar.gz", repos=NULL)
```

## Quick Start

```
# 准备input file,这里maf文件，也可以选择vcf文件
$ FUSCCTNBC_Mutations_forSigMA.maf
# 运行命令
$ Rscript sigma.R -i ./data/FUSCCTNBC_Mutations_forSigMA.maf -o test.csv
# 查询参数
$ Rscript sigma.R -h

```

### 参数
- -i,--input_file,pointer to the directory where input vcf or maf files reside,example './sample.maf' 
- -o,--output_file,the name of output file,default is sigma-results.csv
- -f,--file_type,'maf' or 'vcf'
- -t,--tumor_type, the options are 'bladder', 'bone_other' (Ewing's sarcoma or Chordoma), 'breast',
                'crc','eso', 'gbm', 'lung', 'lymph', 'medullo', 'osteo', 'ovary', 'panc_ad', 'panc_en', 
                'prost', 'stomach', 'thy', or 'uterus'
- -d, --data,the options are 'msk' (for a panel that is similar size to MSK-Impact panel with 410 genes),
                'seqcap' (for whole exome sequencing), 'seqcap_probe' (64 Mb SeqCap EZ Probe v3), or 'wgs' 
                (for whole genome sequencing) 
- -a, --do_assign,a boolean for whether a cutoff should be applied to determine the final decision or just 
                the features should be returned
- -m, --do_mva,a boolean for whether multivariate analysis should be run
- -F, --lite_format, saves the output in a lite format when set to true
- -c,--check_msi, a boolean which determines whether the user wants to identify micro-sattelite instable 
                tumors

## 注意
```
# 直接从github上下载会报错
devtools::install_github("parklab/SigMA")
# 选择本地安装
install.packages("SigMA_1.0.0.0.tar.gz", repos=NULL)
# 依赖包BSgenome.Hsapiens.UCSC.hg19的下载，如果下载不成功，可以本地下载
BiocManager::install("BSgenome.Hsapiens.UCSC.hg19")
```

## 官网地址
http://compbio.med.harvard.edu/sigma/

