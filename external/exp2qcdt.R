#!/usr/bin/env Rscript
######################
### shangjun 
### shangjunv@163.com
######################
library(data.table)
library(magrittr)
library(ggplot2)
library(RColorBrewer)
library(ggthemes)
library(cowplot)

##################### pass in external variables through parameters#######################
make_directories <- function(workdir) {
  dirs <- sapply(c("performance_assessment", "rawqc", "post_alignment_qc", "quantification_qc", "simplified_report"), function(subdir) {
    return(file.path(workdir, subdir))
  })
  
  sapply(dirs, function(dest) {
    dir.create(dest, showWarnings = FALSE)
  })
}

get_exe_path <- function() {
  initial.options <- commandArgs(trailingOnly = FALSE)
  file.arg.name <- "--file="
  script.name <- sub(file.arg.name, "", initial.options[grep(file.arg.name, initial.options)])
  script.dirname <- dirname(script.name)
  return(script.dirname)
}

args <- commandArgs(T)

if (length(args) < 3) {
  stop("At least three argument must be supplied (input file).", call. = FALSE)
} else {
  exp_table_file <- args[1]
  phenotype_file <- args[2]
  result_dir <- args[3]
  
  if (file_test('-f', exp_table_file) &&
      file_test('-f', phenotype_file) &&
      file_test('-d', result_dir)) {
    
    exe_path <- get_exe_path()
    deps_dir <- file.path(exe_path, "exp2qcdt", "dependences")
    ref_data_dir <- file.path(exe_path, "exp2qcdt", 'reference_data')
    dt_exp <- fread(exp_table_file)
    dt_meta <- fread(phenotype_file)
    
    # Prepare directories
    make_directories(result_dir)
    
  } else {
    stop("Please check your arguments.", call. = FALSE)
  }
}


################################### S1 qc metrics ###############################
### S1-1 convert exp matrix--------------------------------
dt_exp_melt <- as.data.table(melt(dt_exp, id = 'GENE_ID'))
setnames(dt_exp_melt, 1:3, c('gene','library','fpkm'))
dt_exp_melt$gene <- as.character(dt_exp_melt$gene)
dt_exp_melt$library <- as.character(dt_exp_melt$library)

# get pairs table-----------------------------------
lst_library_all <- dt_meta$library
dt_pairs = combn(lst_library_all, 2) %>% t %>% as.data.table 
setnames(dt_pairs, 1:2, c('library_A', 'library_B'))
dt_pairs$sampleA = dt_meta[dt_pairs$library_A, on = 'library']$sample
dt_pairs$sampleB = dt_meta[dt_pairs$library_B, on = 'library']$sample
dt_pairs$sample_type <- apply(dt_pairs, 1, function(x){
  if(x['sampleA']==x['sampleB']) return('Intra-sample')
  return('xross-sample')
})
dt_pairs$sample_type <- factor(dt_pairs$sample_type, levels=c('Intra-sample','xross-sample'))
dt_exp_annot <- merge(dt_meta, dt_exp_melt, by = 'library')
exp_fpkm <- dcast(dt_exp_annot, gene~library, value.var = 'fpkm') %>% data.frame(row.names = 1) %>% as.matrix()
exp_fpkm_log <- log(exp_fpkm + 0.01)


### Output results based on metadata
source(paste0(deps_dir, '/one_group_output.R'))
source(paste0(deps_dir, '/two_group_output.R'))
source(paste0(deps_dir, '/more_group_output.R'))
source(paste0(deps_dir, '/config.R'))

if(length(unique(dt_meta$sample)) < 1){
  stop('There is no quantitative qc result')
} else if (length(unique(dt_meta$sample)) == 1){
  get_one_group(dt_exp_annot, dt_pairs, result_dir)
  combine_sd_summary_table(result_dir, sample_num = 1)
  make_score_figure(result_dir, sample_num = 1)
  
} else if (length(unique(dt_meta$sample)) == 2){
  get_one_group(dt_exp_annot, dt_pairs, result_dir)
  get_two_group(dt_exp_annot, dt_pairs, result_dir)
  combine_sd_summary_table(result_dir, sample_num = 2)
  make_score_figure(result_dir, sample_num = 2)
} else if (length(unique(dt_meta$sample)) > 2){
  get_one_group(dt_exp_annot, dt_pairs, result_dir)
  get_two_group(dt_exp_annot, dt_pairs, result_dir)
  get_more_group(exp_fpkm_log, dt_meta, result_dir)
  combine_sd_summary_table(result_dir, sample_num = 4)
  make_score_figure(result_dir, sample_num = 4)
}


