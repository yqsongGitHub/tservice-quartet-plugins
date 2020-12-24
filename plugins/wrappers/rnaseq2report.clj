(ns plugins.wrappers.rnaseq2report
  "A wrapper for rnaseq2report instance."
  (:require [plugins.libs.commons :refer [get-path-variable get-external-root]]
            [tservice.lib.fs :as fs-lib]
            [clojure.java.shell :as shell :refer [sh]]))

(defn call-rnaseq2report!
  "Call rnaseq2report RScript file.
   exp-table-file: An expression table.
   phenotype-file: 
   result-dir: A directory for result files.
  "
  [exp-table-file phenotype-file result-dir]
  (shell/with-sh-env {:PATH   (get-path-variable)
                      :LC_ALL "en_US.utf-8"
                      :LANG   "en_US.utf-8"}
    (let [rnaseq2report-cmd (fs-lib/join-paths (get-external-root) "rnaseq2report" "rnaseq2report.R")
          command ["bash" "-c"
                   (format "Rscript %s %s %s %s" rnaseq2report-cmd exp-table-file phenotype-file result-dir :dir (fs-lib/join-paths (get-external-root) "rnaseq2report"))]
          result  (apply sh command)
          status (if (= (:exit result) 0) "Success" "Error")
          msg (str (:out result) "\n" (:err result))]
      {:status status
       :msg msg})))
