(ns xps2pdf
  (:require [clojure.core.async :as async]
            [me.raynes.fs :as fs]
            [tservice.lib.fs :as fs-lib]
            [clojure.tools.logging :as log]
            [xps :as xps-lib]
            [clojure.data.json :as json]
            [tservice.events :as events]
            [tservice.config :refer [get-workdir]]
            [tservice.routes.specs :as specs]
            [tservice.util :as u]
            [clojure.string :as str]
            [clojure.java.io :as io]))

(def metadata
  {:route ["/xps2pdf"
           {:post {:summary "Convert xps to pdf."
                   :parameters {:body specs/xps2pdf-params-body}
                   :responses {201 {:body {:download_url string? :files [string?] :log_url string?}}}
                   :handler (fn [{{{:keys [filepath]} :body} :parameters}]
                              (let [workdir (get-workdir)
                                    from-path (if (re-matches #"^file:\/\/\/.*" filepath)
                                         ; Absolute path with file://
                                                (str/replace filepath #"^file:\/\/" "")
                                                (fs-lib/join-paths workdir (str/replace filepath #"^file:\/\/" "")))
                                    from-files (if (fs-lib/directory? from-path)
                                                 (filter #(fs-lib/regular-file? %)
                                                         (map #(.getPath %) (file-seq (io/file from-path))))
                                                 [from-path])
                                    relative-dir (fs-lib/join-paths "download" (u/uuid))
                                    to-dir (fs-lib/join-paths workdir relative-dir)
                                    to-files (vec (map #(fs-lib/join-paths to-dir (str (fs/base-name % ".xps") ".pdf")) from-files))
                                    zip-path (fs-lib/join-paths relative-dir "merged.zip")
                                    pdf-path (fs-lib/join-paths relative-dir "merged.pdf")
                                    log-path (fs-lib/join-paths relative-dir "log")]
                                (fs-lib/create-directories! to-dir)
                         ; Launch the batchxps2pdf-convert
                                (spit (fs-lib/join-paths workdir log-path) (json/write-str {:status "Running" :msg ""}))
                                (events/publish-event! :batchxps2pdf-convert {:from-files from-files :to-dir to-dir})
                                {:status 201
                                 :body {:download_url relative-dir
                                        :files (vec (map #(str/replace % (re-pattern workdir) "") to-files))
                                        :log_url log-path
                                        :zip_url zip-path
                                        :pdf_url pdf-path}}))}}]})

(def ^:const xps2pdf-topics
  "The `Set` of event topics which are subscribed to for use in xps2pdf tracking."
  #{:xps2pdf-convert
    :batchxps2pdf-convert})

(def ^:private xps2pdf-channel
  "Channel for receiving event xps2pdf we want to subscribe to for xps2pdf events."
  (async/chan))

;;; ------------------------------------------------ Event Processing ------------------------------------------------

(defn- xps2pdf! [from to]
  (log/info "Converted xps to pdf: " from to)
  (let [out (xps-lib/xps2pdf from to)
        log-path (fs-lib/join-paths (fs-lib/parent-path to) "log")
        exit (:exit out)
        status (if (>= exit 0) "Success" "Error")
        msg (if (>= exit 0) (:err out) (:out out))
        log (json/write-str {:status status :msg msg})]
    (spit log-path log)))

(defn- batch-xps2pdf! [from-files to-dir]
  (log/info "Converted xps files in a zip to pdf files.")
  (let [to-files (vec (map #(fs-lib/join-paths to-dir (str (fs/base-name % ".xps") ".pdf")) from-files))
        zip-path (fs-lib/join-paths to-dir "merged.zip")
        pdf-path (fs-lib/join-paths to-dir "merged.pdf")]
    (doall (pmap #(xps2pdf! %1 %2) from-files to-files))
    (fs-lib/zip-files to-files zip-path)
    (fs-lib/merge-pdf-files to-files pdf-path)))

(defn- process-xps2pdf-event!
  "Handle processing for a single event notification received on the xps2pdf-channel"
  [xps2pdf-event]
  ;; try/catch here to prevent individual topic processing exceptions from bubbling up.  better to handle them here.
  (try
    (when-let [{topic :topic object :item} xps2pdf-event]
      ;; TODO: only if the definition changed??
      (case (events/topic->model topic)
        "xps2pdf"  (xps2pdf! (:from object) (:to object))
        "batchxps2pdf" (batch-xps2pdf! (:from-files object) (:to-dir object))))
    (catch Throwable e
      (log/warn (format "Failed to process xps2pdf event. %s" (:topic xps2pdf-event)) e))))

;;; --------------------------------------------------- Lifecycle ----------------------------------------------------

(defn events-init
  "Automatically called during startup; start event listener for xps2pdf events."
  []
  (events/start-event-listener! xps2pdf-topics xps2pdf-channel process-xps2pdf-event!))
