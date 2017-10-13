(ns apps.service.apps.jobs.submissions.async
  (:use [clojure-commons.core :only [remove-nil-values]]
        [slingshot.slingshot :only [try+ throw+]])
  (:require [clojure.string :as string]
            [clojure.tools.logging :as log]
            [clojure-commons.file-utils :as ft]
            [apps.clients.data-info :as data-info]
            [apps.clients.notifications :as notifications]
            [apps.persistence.jobs :as jp]
            [apps.service.apps.job-listings :as job-listings]
            [apps.service.apps.jobs.submissions.submit :as submit]
            [apps.util.config :as config]))

(defn- max-batch-paths-exceeded
  [max-paths first-list-path first-list-count]
  (throw+
    {:type       :clojure-commons.exception/illegal-argument
     :error      (str "The HT Analysis Path List exceeds the maximum of "
                      max-paths
                      " allowed paths.")
     :path       first-list-path
     :path-count first-list-count}))

(defn- validate-path-lists
  [path-lists]
  (let [[first-list-path first-list] (first path-lists)
        first-list-count             (count first-list)]
    (when (> first-list-count (config/path-list-max-paths))
      (max-batch-paths-exceeded (config/path-list-max-paths) first-list-path first-list-count))
    (when-not (every? (comp (partial = first-list-count) count second) path-lists)
      (throw+ {:type  :clojure-commons.exception/illegal-argument
               :error "All HT Analysis Path Lists must have the same number of paths."}))
    path-lists))

(defn- get-path-list-contents
  [user path]
  (try+
    (when (seq path) (data-info/get-path-list-contents user path))
    (catch Object _
      (log/error (:throwable &throw-context)
                 "job submission failed: Could not get file contents of path list input.")
      (throw+))))

(defn- get-path-list-contents-map
  [user paths]
  (into {} (map (juxt identity (partial get-path-list-contents user)) paths)))

(defn- map-slice
  [m n]
  (->> (map (fn [[k v]] (vector k (nth v n))) m)
       (into {})
       (remove-nil-values)))

(defn- map-slices
  [m]
  (let [max-count (apply max (map (comp count val) m))]
    (mapv (partial map-slice m) (range max-count))))


(defn- substitute-param-values
  [path-map config]
  (->> (map (fn [[k v]] (vector k (get path-map v v))) config)
       (into {})))

(defn- format-submission-in-batch
  [submission job-number path-map]
  (let [job-suffix (str "analysis-" (inc job-number))]
    (assoc (update-in submission [:config] (partial substitute-param-values path-map))
      :group      (config/jex-batch-group-name)
      :name       (str (:name submission) "-" job-suffix)
      :output_dir (ft/path-join (:output_dir submission) job-suffix))))

(defn- submit-job-in-batch
  [apps-client user submission job-number path-map]
  (try+
    (submit/submit-and-register-private-job apps-client
                                            user
                                            (format-submission-in-batch submission job-number path-map))
    (catch Object _
      (log/error (:throwable &throw-context)
                 "batch job submission failed.")
      {:status jp/failed-status})))

(defn- preprocess-batch-submission
  [submission output-dir parent-id]
  (assoc submission
    :output_dir           output-dir
    :parent_id            parent-id
    :create_output_subdir false))

(defn submit-batch-jobs
  "Asynchronously submits sub-jobs for a parent batch job,
   where `ht-paths` is a list of paths of the HT Path List input files,
   `submission` is the original parent submission map,
   `output-dir` is the path to the output folder created for the parent job,
   and `parent-id` is the parent job's ID to use for each sub-job's `parent_id` field.
   A notification will be sent to the user on success or on failure to submit all sub-jobs."
  [apps-client {username :shortUsername email-address :email :as user} ht-paths submission output-dir parent-id]
  (future
    (try+
      (let [path-lists    (validate-path-lists (get-path-list-contents-map user ht-paths))
            path-maps     (map-slices path-lists)
            submission    (preprocess-batch-submission submission output-dir parent-id)
            job-stats     (map-indexed (partial submit-job-in-batch apps-client user submission) path-maps)
            total-jobs    (count job-stats)
            success-count (->> job-stats
                               (filter (comp (partial = jp/submitted-status) :status))
                               count)
            parent-job    (job-listings/list-job apps-client parent-id)
            message       (format "%s %d analyses of %d %s"
                                  (:name parent-job)
                                  success-count
                                  total-jobs
                                  (string/lower-case jp/submitted-status))]
        (if (> success-count 0)
          (notifications/send-job-status-update username email-address parent-job message)
          (throw+ "all batch sub-job submissions failed.")))
      (catch Object _
        (log/error (:throwable &throw-context)
                   "batch job submission failed.")
        (jp/update-job parent-id jp/failed-status nil)
        (notifications/send-job-status-update user (job-listings/list-job apps-client parent-id))))))
