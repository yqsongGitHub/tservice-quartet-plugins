# External Tools
- [RNASEQ2REPORT: Convert RNA-Seq Result Files to a Report](#RNASEQ2REPORT)
- [EXP2QCDT: Convert Expression Table to a Data Table of Quality Control for Quartet](#EXP2QCDT)

## [RNASEQ2REPORT](./rnaseq2report.R)
### Arguments
#### exp_table_file
It is a merged gene expression table.

|GENE_ID|SAMPLE_XXX|SAMPLE_YYY|
|-------|----------|----------|
|ENSGXXX|1.23|23.11|

#### phenotype_file
It is a phenotype table.

|sample_id|group|
|---------|-----|
|SAMPLE_ID|grp1 |

#### result_dir
It is a destination directory.

### Command Example

```
rnaseq2report.R log2fpkm.txt phenotye.txt ./
```

## [EXP2QCDT](./exp2qcdt.sh)
Convert expression table to qc data table.

### Installation
```R
devtools.install_github("clinico-omics/exp2qcdt")
```

### Usage
How to call exp2qcdt from terminal?
```bash
bash exp2qcdt.sh -e ~/Downloads/exp2qcdt/test/fpkm_exp.txt -m ~/Downloads/exp2qcdt/test/meta.txt -o ~/Downloads/exp2qcdt/test/
```