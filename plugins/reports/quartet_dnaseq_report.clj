(ns plugins.reports.quartet-dnaseq-report
  (:require [clojure.core.async :as async]
            [clojure.data.json :as json]
            [clojure.spec.alpha :as s]
            [clojure.tools.logging :as log]
            [spec-tools.core :as st]
            [spec-tools.json-schema :as json-schema]
            [tservice.config :refer [get-workdir env]]
            [tservice.events :as events]
            [tservice.lib.filter-files :as ff]
            [tservice.lib.fs :as fs-lib]
            [tservice.util :as u]
            [tservice.db.handler :as db-handler]
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
   {:spec                (s/and string? #(re-matches #"^[a-zA-Z0-9]+:\/(\/|\.\/)[a-zA-Z0-9_]+.*" %))
    :type                :string
    :description         "File path for covertor."
    :swagger/default     nil
    :reason              "The filepath must be string."}))

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

(s/def ::metadata
  (s/keys :req-un []))

(def quartet-dna-report-params-body
  "A spec for the body parameters."
  (s/keys :req-un [::name ::description ::filepath ::parameters ::metadata]
          :opt-un [::project_id]))

;;; ------------------------------------------------ Event Metadata ------------------------------------------------
(def metadata
  {:route    ["/report/quartet-dnaseq-report"
              {:tags ["Report"]
               :post {:summary "Parse the results of the quartet-dnaseq-qc app and generate the report."
                      :parameters {:body quartet-dna-report-params-body}
                      :responses {201 {:body {:results string? :log string? :report string? :id string?}}}
                      :handler (fn [{{{:keys [name description project_id filepath parameters metadata]} :body} :parameters}]
                                 (let [workdir (get-workdir)
                                       from-path (u/replace-path filepath workdir)
                                       uuid (u/uuid)
                                       relative-dir (fs-lib/join-paths "download" uuid)
                                       to-dir (fs-lib/join-paths workdir relative-dir)
                                       log-path (fs-lib/join-paths to-dir "log")]
                                   (fs-lib/create-directories! to-dir)
                                   (spit log-path (json/write-str {:status "Running" :msg ""}))
                                   (events/publish-event! :quartet_dnaseq_report-convert
                                                          {:datadir from-path
                                                           :metadata metadata
                                                           :parameters parameters
                                                           :dest-dir to-dir})
                                   (db-handler/create-report! name description project_id "quartet_dnaseq_report" relative-dir "multireport")
                                   {:status 201
                                    :body {:results (fs-lib/join-paths relative-dir)
                                           :report (fs-lib/join-paths relative-dir "multiqc.html")
                                           :log (fs-lib/join-paths relative-dir "log")
                                           :id uuid}}))}
               :get {:summary "A json shema for quartet-dnaseq-report."
                     :parameters {}
                     :responses {200 {:body map?}}
                     :handler (fn [_]
                                {:status 200
                                 :body (json-schema/transform quartet-dna-report-params-body)})}}]
   :manifest {:description "Parse the results of the quartet-dna-qc app and generate the report."
              :category "Report"
              :home "https://github.com/clinico-omics/quartet-dnaseq-report"
              :name "Quartet DNA-Seq Report"
              :source "PGx"
              :short_name "quartet-dnaseq-report"
              :icons [{:src "", :type "image/png", :sizes "192x192"}
                      {:src "", :type "image/png", :sizes "192x192"}]
              :author "Yaqing Liu"
              :hidden false
              :id "0a316a318c3305d950df3920bdb2f2b4"
              :app_name "liuyaqing/quartet-dnaseq-report"}})

(def ^:const quartet-dnaseq-report-topics
  "The `Set` of event topics which are subscribed to for use in quartet-dnaseq-report tracking."
  #{:quartet_dnaseq_report-convert})

(def ^:private quartet-dnaseq-report-channel
  "Channel for receiving event quartet-dnaseq-report we want to subscribe to for quartet-dnaseq-report events."
  (async/chan))

;;; ------------------------------------------------ Event Processing ------------------------------------------------

(defn- quartet-dnaseq-report!
  "Chaining Pipeline: filter-files -> copy-files -> multiqc."
  [datadir parameters metadata dest-dir]
  (log/info "Generate quartet dna report: " datadir parameters metadata dest-dir)
  (let [parameters-file (fs-lib/join-paths dest-dir
                                           "results"
                                           "data_generation_information.json")
        files (ff/batch-filter-files datadir
                                     [".*call-fastqc/.*_fastqc.html"
                                      ".*call-fastqc/.*_fastqc.zip"
                                      ".*call-qualimapBAMqc/.*sorted_bamqc_qualimap.zip"
                                      ".*/benchmark_score.txt"
                                      ".*/mendelian_jaccard_index_indel.txt"
                                      ".*/mendelian_jaccard_index_snv.txt"
                                      ".*/postalignment_qc_summary.txt"
                                      ".*/prealignment_qc_summary.txt"
                                      ".*/precision_recall_indel.txt"
                                      ".*/precision_recall_snv.txt"
                                      ".*/variant_calling_qc_summary.txt"])
        result-dir (fs-lib/join-paths dest-dir "results")
        log-path (fs-lib/join-paths dest-dir "log")
        config (fs-lib/join-paths (:tservice-plugin-path env) "config/quartet_dnaseq_report.yaml")]
    (try
      (fs-lib/create-directories! result-dir)
      (spit parameters-file (json/write-str parameters))
      (log/info "Copy files to " result-dir)
      (ff/copy-files! files result-dir {:replace-existing true})
      (let [multiqc-result (mq/multiqc result-dir dest-dir {:config config :template "quartet_dnaseq_report"})
            result {:status (:status multiqc-result)
                    :msg (:msg multiqc-result)}
            log (json/write-str result)]
        (log/info "Status: " result)
        (spit log-path log))
      (catch Exception e
        (let [log (json/write-str {:status "Error" :msg (.toString e)})]
          (log/info "Status: " log)
          (spit log-path log))))))

(defn- process-quartet-dnaseq-report-event!
  "Handle processing for a single event notification received on the quartet-dnaseq-report-channel"
  [quartet-dnaseq-report-event]
  ;; try/catch here to prevent individual topic processing exceptions from bubbling up.  better to handle them here.
  (try
    (when-let [{topic :topic object :item} quartet-dnaseq-report-event]
      ;; TODO: only if the definition changed??
      (case (events/topic->model topic)
        "quartet_dnaseq_report"  (quartet-dnaseq-report! (:datadir object) (:parameters object) (:metadata object) (:dest-dir object))))
    (catch Throwable e
      (log/warn (format "Failed to process quartet-dnaseq-report event. %s" (:topic quartet-dnaseq-report-event)) e))))

;;; --------------------------------------------------- Lifecycle ----------------------------------------------------

(defn events-init
  "Automatically called during startup; start event listener for quartet-dnaseq-report events."
  []
  (events/start-event-listener! quartet-dnaseq-report-topics quartet-dnaseq-report-channel process-quartet-dnaseq-report-event!))
