(ns plugins.libs.commons
  (:require [tservice.config :refer [env get-plugin-dir]]
            [clojure.java.shell :as shell :refer [sh]]
            [clojure.data.csv :as csv]
            [tservice.lib.fs :as fs-lib]
            [clojure.string :as clj-str]
            [clojure.java.io :as io])
  (:import [org.apache.commons.io.input BOMInputStream]))

(defn get-path-variable
  []
  (let [external-bin (get-in env [:external-bin])
        sys-path (System/getenv "PATH")]
    (if external-bin
      (str external-bin ":" sys-path)
      sys-path)))

(defn get-external-root
  []
  (fs-lib/join-paths (get-plugin-dir) "external"))

(defn exist-bin?
  [name]
  (= 0 (:exit (sh "which" name))))

(defn csv-data->maps [csv-data]
  (map zipmap
       (->> (first csv-data) ;; First row is the header
            (map keyword)    ;; Drop if you want string keys instead
            repeat)
       (rest csv-data)))

(defn bom-reader
  "Remove `Byte Order Mark` and return reader"
  [filepath]
  (-> filepath
      io/input-stream
      BOMInputStream.
      io/reader))

(defn guess-separator
  [filepath]
  (with-open [reader (bom-reader filepath)]
    (let [header (first (line-seq reader))
          seps [\tab \, \; \space]
          sep-map (->> (map #(hash-map % (count (clj-str/split header (re-pattern (str %))))) seps)
                       (into {}))]
      (key (apply max-key val sep-map)))))

(defn read-csv
  [^String file]
  (when (.isFile (io/file file))
    (with-open
     [reader (io/reader file)]
      (doall
       (->> (csv/read-csv reader :separator (guess-separator file))
            csv-data->maps)))))

(defn write-csv!
  "Write row-data to a csv file, row-data is a vector that each element is a map."
  [path row-data]
  (let [columns (keys (first row-data))
        headers (map name columns)
        rows (mapv #(mapv % columns) row-data)]
    (with-open [file (io/writer path)]
      (csv/write-csv file (cons headers rows) :separator \tab))))
