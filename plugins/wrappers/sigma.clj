(ns plugins.wrappers.sigma
  "A wrapper for sigma instance."
  (:require [plugins.libs.commons :refer [get-path-variable get-external-root]]
            [clojure.java.shell :as shell :refer [sh]]
            [tservice.lib.fs :as fs-lib]
            [clojure.string :as str]))

(defn hashmap->parameters
  "{ '-d' 'true' '-o' 'output' } -> '-d true -o output'"
  [coll]
  (str/join " " (map #(str/join " " %) (into [] coll))))

(defn call-sigma!
  "Call sigma RScript file.
   input-file(-i): intput file path.
   output-file(-o): output file path.
   file-type(-f): 'maf', 'vcf'
   tumor-type(-t): the options are 'bladder', 'bone_other' (Ewing's sarcoma or Chordoma), 'breast', 'crc', 'eso', 'gbm', 'lung', 'lymph', 'medullo', 'osteo', 'ovary', 'panc_ad', 'panc_en', 'prost', 'stomach', 'thy', or 'uterus'.
   data(-d): the options are 'msk' (for a panel that is similar size to MSK-Impact panel with 410 genes), 'seqcap' (for whole exome sequencing), 'seqcap_probe' (64 Mb SeqCap EZ Probe v3), or 'wgs' (for whole genome sequencing)
   do-assign(-a): boolean for whether a cutoff should be applied to determine the final decision or just the features should be returned
   do-mva(-m): a boolean for whether multivariate analysis should be run
   lite-format(-F): saves the output in a lite format when set to true
   check-msi(-c): is a boolean which determines whether the user wants to identify micro-sattelite instable tumors
  "
  [input-file output-file parameters]
  (shell/with-sh-env {:PATH   (get-path-variable)
                      :LC_ALL "en_US.utf-8"
                      :LANG   "en_US.utf-8"}
    (let [{:keys [file-type tumor-type data do-assign do-mva lite-format check-msi] :or {file-type "maf", tumor-type "breast", data "msk", do-assign true, do-mva true, lite-format true, check-msi true}} parameters
          coll {"-i" input-file
                "-o" output-file
                "-f" file-type
                "-t" tumor-type
                "-d" data
                "-a" do-assign
                "-m" do-mva
                "-F" lite-format
                "-c" check-msi}
          sigma-cmd (fs-lib/join-paths (get-external-root) "sigma" "sigma.R")
          command ["bash" "-c" (format "Rscript %s %s" sigma-cmd (hashmap->parameters coll)) :dir (fs-lib/join-paths (get-external-root) "sigma")]
          result  (apply sh command)
          status (if (= (:exit result) 0) "Success" "Error")
          msg (str (:out result) "\n" (:err result))]
      {:status status
       :msg msg})))
