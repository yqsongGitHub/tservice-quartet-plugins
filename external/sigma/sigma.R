#!/usr/bin/env Rscript
library(SigMA)
library(optparse)

# ------------------- Main Analysis -------------------
runSigMA <- function(inputdir,
                    outputfile = "sigma-results.csv",
                    file_type = "maf",
                    tumor_type = "breast",
                    data = "seqcap",
                    do_assign = TRUE,
                    do_mva = TRUE,
                    lite_format = TRUE,
                    check_msi = TRUE
                    ) {
  genomes_matrix <- make_matrix(inputdir, file_type = file_type)
  genomes <- conv_snv_matrix_to_df(genomes_matrix)
  genome_file <- gsub(".maf", "_maf.csv", inputdir)
  write.table(genomes,
              genome_file,
              sep = ",",
              row.names = FALSE,
              col.names = TRUE,
              quote = FALSE)

  run(genome_file,
      output_file = outputfile,
      tumor_type = tumor_type,
      data = data,
      do_assign = do_assign,
      do_mva = do_mva,
      lite_format = lite_format,
      check_msi = check_msi)
  file.remove(genome_file)
}


option_list <- list(
  make_option(c("-i", "--input_file"), type = "character", default = NULL,
              help = "pointer to the directory where input vcf or maf files reside,example './sample.maf' ",
              metavar = "character"),
  make_option(c("-o", "--output_file"), type = "character", default = "sigma-results.csv",
              help = "the name of output file [default= %default]",
              metavar = "character"),
  make_option(c("-f", "--file_type"), type = "character", default = "maf",
              help = " 'maf' or 'vcf' [default= %default]", metavar = "character"),
  make_option(c("-t", "--tumor_type"), type = "character", default = "breast",
              help = "the options are 'bladder', 'bone_other' (Ewing's sarcoma or Chordoma), 'breast',
                'crc','eso', 'gbm', 'lung', 'lymph', 'medullo', 'osteo', 'ovary', 'panc_ad', 'panc_en', 
                'prost', 'stomach', 'thy', or 'uterus'.[default= %default]",
              metavar = "character"),
  make_option(c("-d", "--data"), type = "character", default = "seqcap",
              help = "the options are 'msk' (for a panel that is similar size to MSK-Impact panel with 410 genes),
                'seqcap' (for whole exome sequencing), 'seqcap_probe' (64 Mb SeqCap EZ Probe v3), or 'wgs' 
                (for whole genome sequencing) [default= %default]",
              metavar = "character"),
  make_option(c("-a", "--do_assign"), type = "logical", default = TRUE,
              help = "a boolean for whether a cutoff should be applied to determine the final decision or just 
                the features should be returned [default= %default]"),
  make_option(c("-m", "--do_mva"), type = "logical", default = TRUE,
              help = "a boolean for whether multivariate analysis should be run [default= %default]"),
  make_option(c("-F", "--lite_format"), type = "logical", default = TRUE,
              help = "saves the output in a lite format when set to true [default= %default]"),
  make_option(c("-c", "--check_msi"), type = "logical", default = TRUE,
              help = "a boolean which determines whether the user wants to identify micro-sattelite instable tumors[default= %default]")
)

opt_parser <- OptionParser(option_list = option_list)
opt <- parse_args(opt_parser)

if (is.null(opt$input_file)) {
  print_help(opt_parser)
  stop("At least one argument must be supplied (input file).n", call. = FALSE)
}

print(sprintf("Options: %s = %s", names(opt), opt))

runSigMA(inputdir = opt$input_file,
         outputfile = opt$output_file,
         file_type = opt$file_type,
         tumor_type = opt$tumor_type,
         data = opt$data,
         do_assign = opt$do_assign,
         do_mva = opt$do_mva,
         lite_format = opt$lite_format,
         check_msi = opt$check_msi)

