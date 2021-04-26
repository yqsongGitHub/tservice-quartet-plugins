(ns plugins.reports.quartet-rnaseq-report
  (:require [clojure.core.async :as async]
            [clojure.data.json :as json]
            [clojure.spec.alpha :as s]
            [clojure.tools.logging :as log]
            [plugins.libs.commons :as comm :refer [get-path-variable]]
            [plugins.wrappers.exp2qcdt :as exp2qcdt]
            [plugins.wrappers.merge-exp :as me]
            [spec-tools.core :as st]
            [spec-tools.json-schema :as json-schema]
            [tservice.config :refer [get-workdir env]]
            [tservice.events :as events]
            [tservice.lib.filter-files :as ff]
            [tservice.lib.fs :as fs-lib]
            [tservice.util :as u]
            [tservice.db.handler :as db-handler]
            [clojure.java.shell :as shell :refer [sh]]
            [tservice.vendor.multiqc :as mq]))

;;; ------------------------------------------------ Event Specs ------------------------------------------------
(s/def ::name
  (st/spec
   {:spec                string?
    :type                :string
    :description         "The name of the report"
    :swagger/default     ""
    :reason              "Not a valid report name"}))

(s/def ::description
  (st/spec
   {:spec                string?
    :type                :string
    :description         "Description of the report"
    :swagger/default     ""
    :reason              "Not a valid description."}))

(s/def ::project_id
  (st/spec
   {:spec                #(some? (re-matches #"[a-f0-9]{8}(-[a-f0-9]{4}){4}[a-f0-9]{8}" %))
    :type                :string
    :description         "project-id"
    :swagger/default     "40644dec-1abd-489f-a7a8-1011a86f40b0"
    :reason              "Not valid a project-id."}))

(s/def ::filepath
  (st/spec
   {:spec                (s/and string? #(re-matches #"^[a-zA-Z0-9]+:\/\/(\/|\.\/)[a-zA-Z0-9_]+.*" %))
    :type                :string
    :description         "File path for covertor."
    :swagger/default     nil
    :reason              "The filepath must be string."}))

(s/def ::group
  (st/spec
   {:spec                string?
    :type                :string
    :description         "A group name which is matched with library."
    :swagger/default     []
    :reason              "The group must a string."}))

(s/def ::library
  (st/spec
   {:spec                string?
    :type                :string
    :description         "A library name."
    :swagger/default     []
    :reason              "The library must a string."}))

(s/def ::sample
  (st/spec
   {:spec                string?
    :type                :string
    :description         "A sample name."
    :swagger/default     []
    :reason              "The sample name must a string."}))

(s/def ::metadat-item
  (s/keys :req-un [::library
                   ::group
                   ::sample]))

(s/def ::metadata
  (s/coll-of ::metadat-item))

(s/def ::lab
  (st/spec
   {:spec                string?
    :type                :string
    :description         "Lab name."
    :swagger/default     []
    :reason              "The lab_name must be string."}))

(s/def ::sequencing_platform
  (st/spec
   {:spec                string?
    :type                :string
    :description         "Sequencing Platform."
    :swagger/default     []
    :reason              "The sequencing_platform must be string."}))

(s/def ::sequencing_method
  (st/spec
   {:spec                string?
    :type                :string
    :description         "Sequencing Method"
    :swagger/default     []
    :reason              "The sequencing_method must be string."}))

(s/def ::library_protocol
  (st/spec
   {:spec                string?
    :type                :string
    :description         "Library protocol."
    :swagger/default     []
    :reason              "The library_protocol must be string."}))

(s/def ::library_kit
  (st/spec
   {:spec                string?
    :type                :string
    :description         "Library kit."
    :swagger/default     []
    :reason              "The library_kit must be string."}))

(s/def ::read_length
  (st/spec
   {:spec                string?
    :type                :string
    :description         "Read length"
    :swagger/default     []
    :reason              "The read_length must be string."}))

(s/def ::date
  (st/spec
   {:spec                string?
    :type                :string
    :description         "Date"
    :swagger/default     []
    :reason              "The date must be string."}))

(s/def ::parameters
  (s/keys :req-un [::lab
                   ::sequencing_platform
                   ::sequencing_method
                   ::library_protocol
                   ::library_kit
                   ::read_length
                   ::date]))

(def quartet-rna-report-params-body
  "A spec for the body parameters."
  (s/keys :req-un [::name ::description ::filepath ::metadata ::parameters]
          :opt-un [::project_id]))

;;; ------------------------------------------------ Event Metadata ------------------------------------------------
(def metadata
  {:route    ["/report/quartet-rnaseq-report"
              {:tags ["Report"]
               :post {:summary "Parse the results of the quartet-rnaseq-qc app and generate the report."
                      :parameters {:body quartet-rna-report-params-body}
                      :responses {201 {:body {:results string? :log string? :report string? :id string?}}}
                      :handler (fn [{{{:keys [name description project_id filepath metadata parameters]} :body} :parameters}]
                                 (let [workdir (get-workdir)
                                       from-path (u/replace-path filepath workdir)
                                       uuid (u/uuid)
                                       relative-dir (fs-lib/join-paths "download" uuid)
                                       to-dir (fs-lib/join-paths workdir relative-dir)
                                       log-path (fs-lib/join-paths to-dir "log")]
                                   (fs-lib/create-directories! to-dir)
                                   (spit log-path (json/write-str {:status "Running" :msg ""}))
                                   (events/publish-event! :quartet_rnaseq_report-convert
                                                          {:datadir from-path
                                                           :parameters parameters
                                                           :metadata metadata
                                                           :dest-dir to-dir})
                                   (db-handler/create-report! name description project_id "quartet_rnaseq_report" relative-dir "multireport")
                                   {:status 201
                                    :body {:results (fs-lib/join-paths relative-dir)
                                           :report (fs-lib/join-paths relative-dir "multiqc.html")
                                           :log (fs-lib/join-paths relative-dir "log")
                                           :id uuid}}))}
               :get {:summary "A json shema for quartet-rnaseq-report."
                     :parameters {}
                     :responses {200 {:body map?}}
                     :handler (fn [_]
                                {:status 200
                                 :body (json-schema/transform quartet-rna-report-params-body)})}}]
   :manifest {:description "Parse the results of the quartet-rna-qc app and generate the report."
              :category "Report"
              :home "https://github.com/clinico-omics/quartet-rnaseq-report"
              :name "Quartet RNA-Seq Report"
              :source "PGx"
              :short_name "quartet-rnaseq-report"
              :icons [{:src "", :type "image/png", :sizes "192x192"}
                      {:src "", :type "image/png", :sizes "192x192"}]
              :author "Jun Shang"
              :hidden false
              :id "f65d87fd3dd2213d91bb15900ba57c11"
              :app_name "shangjun/quartet-rnaseq-report"}})

(def ^:const quartet-rnaseq-report-topics
  "The `Set` of event topics which are subscribed to for use in quartet-rnaseq-report tracking."
  #{:quartet_rnaseq_report-convert})

(def ^:private quartet-rnaseq-report-channel
  "Channel for receiving event quartet-rnaseq-report we want to subscribe to for quartet-rnaseq-report events."
  (async/chan))

;;; ------------------------------------------------ Event Processing ------------------------------------------------

(defn- decompression-tar
  [filepath]
  (shell/with-sh-env {:PATH   (get-path-variable)
                      :LC_ALL "en_US.utf-8"
                      :LANG   "en_US.utf-8"}
    (let [command ["bash" "-c"
                   (format "tar -xvf %s -C %s" filepath (fs-lib/parent-path filepath))]
          result  (apply sh command)
          status (if (= (:exit result) 0) "Success" "Error")
          msg (str (:out result) "\n" (:err result))]
      {:status status
       :msg msg})))

(defn- filter-mkdir-copy
  [fmc-datadir fmc-patterns fmc-destdir fmc-newdir]
  (let [files-keep (ff/batch-filter-files fmc-datadir fmc-patterns)
        files-keep-dir (fs-lib/join-paths fmc-destdir fmc-newdir)]
    (fs-lib/create-directories! files-keep-dir)
    (ff/copy-files! files-keep files-keep-dir {:replace-existing true})))

(defn- quartet-rnaseq-report!
  "Chaining Pipeline: filter-files -> copy-files -> merge_exp_file -> exp2qcdt -> multiqc."
  [datadir parameters metadata dest-dir]
  (log/info "Generate quartet rnaseq report: " datadir parameters metadata dest-dir)
  (let [metadata-file (fs-lib/join-paths dest-dir
                                         "results"
                                         "metadata.csv")
        parameters-file (fs-lib/join-paths dest-dir
                                           "results"
                                           "general-info.json")
        ballgown-dir (fs-lib/join-paths dest-dir "ballgown")
        count-dir (fs-lib/join-paths dest-dir "count")
        exp-fpkm-filepath (fs-lib/join-paths dest-dir "fpkm.txt")
        exp-count-filepath (fs-lib/join-paths dest-dir "count.txt")
        result-dir (fs-lib/join-paths dest-dir "results")
        log-path (fs-lib/join-paths dest-dir "log")
        config (fs-lib/join-paths (:tservice-plugin-path env) "plugins/config/quartet_rnaseq_report.yaml")]
    (try
      (fs-lib/create-directories! result-dir)
      (log/info "Merge these files")
      (log/info "Merge gene experiment files from ballgown directory to a experiment table: " ballgown-dir exp-fpkm-filepath)
      (log/info "Merge these files")
      (log/info "Merge gene experiment files from count directory to a experiment table: " count-dir exp-count-filepath)
      (filter-mkdir-copy datadir [".*call-ballgown/.*.txt"] dest-dir "ballgown")
      (filter-mkdir-copy datadir [".*call-count/.*gene_count_matrix.csv"] dest-dir "count")
      (filter-mkdir-copy datadir [".*call-qualimapBAMqc/.*tar.gz"] dest-dir "results/post_alignment_qc/qualimap_bam")
      (filter-mkdir-copy datadir [".*call-qualimapRNAseq/.*tar.gz"] dest-dir "results/post_alignment_qc/qualimap_rnaseq")
      (filter-mkdir-copy datadir [".*call-fastqc/.*.zip"] dest-dir "results/rawqc/fastqc")
      (filter-mkdir-copy datadir [".*call-fastqscreen/.*.txt"] dest-dir "results/rawqc/fastq_screen")
      (me/merge-exp-files! (ff/list-files ballgown-dir {:mode "file"}) exp-fpkm-filepath)
      (me/merge-exp-files! (ff/list-files count-dir {:mode "file"}) exp-count-filepath)
      (spit parameters-file (json/write-str parameters))
      (comm/write-csv! metadata-file metadata)
      ;;(decompression-tar files-qualimap-bam)
      ;;(decompression-tar files-qualimap-RNA)
      (doseq [files-qualimap-bam-tar (ff/batch-filter-files (fs-lib/join-paths dest-dir "results/post_alignment_qc/qualimap_bam") [".*tar.gz"])]
        (decompression-tar files-qualimap-bam-tar))
      (doseq [files-qualimap-RNA-tar (ff/batch-filter-files (fs-lib/join-paths dest-dir "results/post_alignment_qc/qualimap_rnaseq") [".*tar.gz"])]
        (decompression-tar files-qualimap-RNA-tar))
      (let [exp2qcdt-result (exp2qcdt/call-exp2qcdt! exp-fpkm-filepath exp-count-filepath metadata-file result-dir)
            multiqc-result (if (= (:status exp2qcdt-result) "Success")
                             (mq/multiqc result-dir dest-dir {:config config :template "default" :title "Quartet RNA report"})
                             ;;(mq/multiqc result-dir)
                             exp2qcdt-result)
            result {:status (:status multiqc-result)
                    :msg (:msg multiqc-result)}
            log (json/write-str result)]
        (log/info "Status: " result)
        (spit log-path log))
      (catch Exception e
        (let [log (json/write-str {:status "Error" :msg (.toString e)})]
          (log/info "Status: " log)
          (spit log-path log))))))

(defn- process-quartet-rnaseq-report-event!
  "Handle processing for a single event notification received on the quartet-rnaseq-report-channel"
  [quartet-rnaseq-report-event]
  ;; try/catch here to prevent individual topic processing exceptions from bubbling up.  better to handle them here.
  (try
    (when-let [{topic :topic object :item} quartet-rnaseq-report-event]
      ;; TODO: only if the definition changed??
      (case (events/topic->model topic)
        "quartet_rnaseq_report" (quartet-rnaseq-report! (:datadir object) (:parameters object) (:metadata object) (:dest-dir object))))
    (catch Throwable e
      (log/warn (format "Failed to process quartet-rnaseq-report event. %s" (:topic quartet-rnaseq-report-event)) e))))

;;; --------------------------------------------------- Lifecycle ----------------------------------------------------

(defn events-init
  "Automatically called during startup; start event listener for quartet-rnaseq-report events."
  []
  (events/start-event-listener! quartet-rnaseq-report-topics quartet-rnaseq-report-channel process-quartet-rnaseq-report-event!))