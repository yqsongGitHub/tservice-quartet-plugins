# External Tools
- [RNASEQ2REPORT](#RNASEQ2REPORT)
- [EXP2QCDT](#EXP2QCDT)

## RNASEQ2REPORT
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

## [EXP2QCDT](https://github.com/clinico-omics/exp2qcdt)
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