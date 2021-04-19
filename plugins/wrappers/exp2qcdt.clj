(ns plugins.wrappers.exp2qcdt
  "A wrapper for exp2qcdt tool."
  (:require [plugins.libs.commons :refer [get-path-variable get-external-root]]
            [tservice.lib.fs :as fs-lib]
            [clojure.java.shell :as shell :refer [sh]]))

(defn call-exp2qcdt!
  "Call exp2qcdt bash script.
   exp-file: FPKM file , each row is the expression values of a gene and each column is a library.
   cnt-file: Count file, each row is the expression values of a gene and each column is a library.
   meta-file: Need to contain three columns: library, group, and sample and library names must be matched with the column names in the `exp-file`.
   result-dir: A directory for result files.
  "
  [exp-file cnt-file meta-file result-dir]
  (shell/with-sh-env {:PATH   (get-path-variable)
                      :LC_ALL "en_US.utf-8"
                      :LANG   "en_US.utf-8"}
    (let [exp2qcdt-cmd (fs-lib/join-paths (get-external-root) "exp2qcdt" "exp2qcdt.sh")
          command ["bash" "-c"
                   (format "%s -e %s -c %s -m %s -o %s" exp2qcdt-cmd exp-file cnt-file meta-file result-dir) :dir (fs-lib/join-paths (get-external-root) "exp2qcdt")]
          result  (apply sh command)
          status (if (= (:exit result) 0) "Success" "Error")
          msg (str (:out result) "\n" (:err result))]
      {:status status
       :msg msg})))

