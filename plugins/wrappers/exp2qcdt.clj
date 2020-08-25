(ns exp2qcdt
  "A wrapper for exp2qcdt tool."
  (:require [commons :refer [get-path-variable]]
            [clojure.java.shell :as shell :refer [sh]]))

(defn call-exp2qcdt!
  "Call exp2qcdt bash script.
   exp-file: An expression table, each row is the expression values of a gene and each column is a library.
   meta-file: Need to contain three columns: library, group, and sample and library names must be matched with the column names in the `exp-file`.
   result-dir: A directory for result files.
  "
  [exp-file meta-file result-dir]
  (shell/with-sh-env {:PATH   (get-path-variable)
                      :LC_ALL "en_US.utf-8"
                      :LANG   "en_US.utf-8"}
    (let [command ["bash" "-c"
                   (format "exp2qcdt.sh -e %s -m %s -d %s" exp-file meta-file result-dir)]
          result  (apply sh command)
          status (if (= (:exit result) 0) "Success" "Error")
          msg (str (:out result) "\n" (:err result))]
      {:status status
       :msg msg})))
