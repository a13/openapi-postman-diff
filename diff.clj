#!/usr/bin/env bb
(ns op.diff
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.data.csv :as csv]
            [clojure.java.io :as io]
            [clojure.tools.cli :as cli]))

(defn- prepare-oapi-path [path]
  (str/replace path #"\{\w+\}" "{}"))

(defn openapi-endpoints
  "Create a hashmap with unified-path:original-path pairs."
  [openapi-file]
  (let [paths (-> openapi-file
                  slurp
                  json/decode
                  (get "paths")
                  keys)]
    (zipmap (map prepare-oapi-path paths)
            paths)))

(defn prepare-pm-path [path]
  (-> path
      (str/replace #"\{\{Url}\}" "")
      (str/replace #"\{\{\w+\}\}" "{}")))

(defn pm-item->path [{:keys [name request]}]
  (let [{:keys [url]} request
        {:keys [raw]} url
        path (prepare-pm-path raw)]
    [path {:name name
           :raw raw}]))

(defn postman-paths [postman-file]
  (let [items (-> postman-file
                  slurp
                  (json/decode true)
                  (get-in [:item]))]
    (->> items
         (map pm-item->path)
         (into {}))))

(defn path->row [path pm-paths os-paths]
  (let [{:keys [name raw]} (get pm-paths path)
        oapi-path (get os-paths path)]
    {:postman-name (or name "")
     :postman-url (or raw "")
     :openapi-url (or oapi-path "")}))

(defn write-csv [path row-data]
  (let [columns [:openapi-url :postman-name :postman-url]
        headers (map name columns)
        rows (mapv #(mapv % columns) row-data)]
    (with-open [file (io/writer path)]
      (csv/write-csv file (cons headers rows)))))

(def cli-options
  ;; An option with a required argument
  [["-o" "--openapi PATH" "OpenAPI JSON path"]
   ["-p" "--postman PATH" "Postman JSON path"]
   ["-h" "--help"]])


(let [{:keys [options summary]} (cli/parse-opts *command-line-args* cli-options)
      {:keys [openapi postman help]} options]
  (if (or help (and (str/blank? openapi)
                    (str/blank? postman)))
    (println (str summary))
    (let [pm-paths (postman-paths postman)
          os-paths (openapi-endpoints openapi)
          out (->> (concat (keys os-paths) (keys pm-paths))
                   distinct
                   sort
                   (map #(path->row % pm-paths os-paths)))]
      (write-csv "diff.csv" out))))
