(ns munge.io.data-frame
  (:require [clojure.string :refer [split join trim]]
            [clojure.java.io :as io]
            [clojure.data.csv :as csv]
            [clojure.core.typed :as t]
            [schema.core :as s]
            [schema.coerce :as s.coerce]
            [munge.schema :refer [Nil]]))

(defn quoted-str [^String s] (and (.startsWith s "\"") (.endsWith s "\"")))

(def UnquotedStr (s/pred #(and (string? %) (not (quoted-str %))) "unquoted-string"))
(def +unquoted-coercion+ {UnquotedStr (fn [^String s] (if (quoted-str s)
                                                (.substring s 1 (- (.length s) 1))
                                                s))})
(defn int->boolean
  [x]
  (when (integer? x)
    (if (or (= -1 x)
            (= 0 x))
      false
      true)))
(def +bool-coercion+ {s/Bool (fn [x] (cond
                                      (string? x) (s.coerce/string->boolean x)
                                      (integer? x) (int->boolean x)))})
(def +row-coercions+ (merge s.coerce/+string-coercions+
                            +unquoted-coercion+
                            +bool-coercion+
                            {s/Int (fn [^String x]
                                      (if (.equals "NA" x)
                                        -1
                                        ;;(s.coerce/edn-read-string x)
                                        (Integer/parseInt x)
                                        ))}))

(s/defn read-data-line :- [s/Str]
  [separator :- Character
   line :- s/Str]
  (first (csv/read-csv line :separator separator)))

;; TODO: not resilient to multiple line records
;; TODO: return type is dependent on arguments
;; TODO: row-schema should be a s/Schema, but doesn't seem to be supported by Schema lib??
(s/defn read-data-frame :- [[s/Any]]
  [separator :- Character
   header? :- s/Bool
   col-names :- [(s/either s/Str s/Keyword)]
   row-schema :- s/Any
   rdr :- java.io.Reader]  
  (let [data (csv/read-csv rdr :separator separator)
        ;;data (for [l lines] (read-data-line separator l))
        num-cols (-> data first count)
        default-col-names (vec (for [i (range num-cols)] (format "col-%d" i)))
        ;; Schema must not be a lazy-seq
        unquoted-row-schema (vec (for [c-name default-col-names] (s/one UnquotedStr c-name)))
        col-names (or col-names default-col-names)
        row-schema (or row-schema unquoted-row-schema)
        parse-row (s.coerce/coercer row-schema +row-coercions+)
        header (vec (map keyword (if header?
                                   ((s.coerce/coercer unquoted-row-schema +unquoted-coercion+) (first data))
                                   col-names)))
        rows (if header? (rest data) data)]
    (->> rows
         (map (comp vec parse-row))
         (vec))))

;; TODO: move to csv lib?
(defn write-data-frame [w headers records separator]
  (when headers
    (.write ^java.io.Writer w (format "%s\n" (->> headers (map (comp (partial format "\"%s\"") name)) (join separator)))))
  (doseq [r records]
    (.write ^java.io.Writer w (format "%s\n" (->> r (map (partial format "\"%s\"")) (join separator))))))

(comment
  (s/defn write-data-frame :- s/Any
    [w :- java.io.Writer
     headers :- (s/either [(s/either s/Str s/Keyword)] Nil)
     records :- [[s/Any]]
     separator :- Character]
    (let [data (if (nil? headers)
                 records
                 (cons (map name headers) (seq records)))]
      (csv/write-csv w data :separator separator))))

;; returns [(s/either {s/Keyword s/Str} [s/Str])]
(defn load-data-frame
  "Load a data frame from the given file.
  Arguments:
    separator - the separator used
    header? - does the first line provide column names
    col-names - column names to use, will override header names
    row-schema - types for the columns, will return strings if not provided"
  [path & {:keys [separator header? col-names row-schema]
           :or {separator \,
                header? false
                col-names nil
                row-schema nil}}] 
  (with-open [r (io/reader path)]
    (read-data-frame separator header? col-names row-schema r)))

(t/defn save-data-frame
  [path :- java.io.File
   headers :- (t/Seq t/Kw)
   records :- (t/Seq (t/Coll))
   separator :- Character]
  (with-open [w (io/writer path)]
    (write-data-frame w headers records separator)))
