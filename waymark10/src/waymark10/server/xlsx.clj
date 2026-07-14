(ns waymark10.server.xlsx
  "A minimal xlsx codec — one sheet of plain values, no styles, no
  formulas — hand-rolled over the JDK's own zip and XML (an xlsx file
  is a zip of XML parts), in the engine's no-new-dependencies
  posture: POI is a freighter and the worksheet seam needs a rowboat.

  write-sheet: rows of nil | string | number | boolean → xlsx bytes.
  Strings travel as inline strings (no sharedStrings part to build),
  numbers and booleans as themselves, nils as absent cells.

  read-sheet: xlsx bytes → rows of nil | String | Double | Boolean.
  Reads what Excel actually re-saves: sharedStrings (t=\"s\"), inline
  strings, formula caches (t=\"str\"), booleans, and bare numerics.
  Excel stores an edited date as a NUMBER (days since 1899-12-30);
  this codec never guesses which numbers are dates — the caller knows
  each column's declared type and converts with serial->instant-str.

  Hygiene at the upload boundary: DTDs are refused (no XXE), and the
  uncompressed read is capped (no zip bombs)."
  (:require [clojure.string :as str])
  (:import (java.io ByteArrayInputStream ByteArrayOutputStream)
           (java.nio.charset StandardCharsets)
           (java.time Duration LocalDate ZoneOffset)
           (java.util.zip ZipEntry ZipInputStream ZipOutputStream)
           (javax.xml XMLConstants)
           (javax.xml.parsers DocumentBuilderFactory)
           (org.w3c.dom Document Element Node NodeList)))

(set! *warn-on-reflection* true)

;; ── shared spelling ─────────────────────────────────────────────────

(defn- xml-escape ^String [s]
  (-> (str s)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn col-letters
  "0 → A, 25 → Z, 26 → AA — the spreadsheet's column spelling."
  ^String [i]
  (loop [i (long i) acc ""]
    (let [acc (str (char (+ 65 (mod i 26))) acc)]
      (if (< i 26) acc (recur (dec (quot i 26)) acc)))))

(defn- col-index
  "\"A1\"/\"BC23\" → the 0-based column index its letters spell."
  ^long [^String cell-ref]
  (reduce (fn [^long acc ch]
            (let [c (long (int ch))
                  c (if (<= 97 c 122) (- c 32) c)]     ; upcase a-z
              (if (<= 65 c 90)
                (+ (* 26 acc) (- c 64))
                (reduced acc))))
          0 cell-ref))

;; ── the writer ──────────────────────────────────────────────────────

(def ^:private content-types
  "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>
<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/><Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/></Types>")

(def ^:private root-rels
  "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>
<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>")

(def ^:private workbook-xml
  "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>
<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet name=\"Sheet1\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>")

(def ^:private workbook-rels
  "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>
<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/></Relationships>")

(defn- number-str
  "A number without Clojure's reader dressing: longs bare, doubles
  bare, BigDecimal in plain (never scientific) spelling."
  ^String [n]
  (cond
    (instance? BigDecimal n) (.toPlainString ^BigDecimal n)
    (and (double? n) (== n (Math/floor n))
         (not (Double/isInfinite ^double n))
         (<= (Math/abs ^double n) 9.007199254740992E15))
    (str (long n))
    :else (str n)))

(defn- cell-xml ^String [row-n col-n v]
  (let [ref (str (col-letters col-n) row-n)]
    (cond
      (nil? v) nil
      (boolean? v) (str "<c r=\"" ref "\" t=\"b\"><v>" (if v 1 0) "</v></c>")
      (number? v) (str "<c r=\"" ref "\"><v>" (number-str v) "</v></c>")
      :else (str "<c r=\"" ref "\" t=\"inlineStr\"><is>"
                 "<t xml:space=\"preserve\">" (xml-escape v) "</t>"
                 "</is></c>"))))

(defn- sheet-xml ^String [rows]
  (str "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
       "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>"
       (str/join
        (map-indexed
         (fn [i row]
           (let [row-n (inc i)]
             (str "<row r=\"" row-n "\">"
                  (str/join (keep-indexed #(cell-xml row-n %1 %2) row))
                  "</row>")))
         rows))
       "</sheetData></worksheet>"))

(defn write-sheet
  "Rows (seqs of nil | string | number | boolean) → xlsx bytes, one
  sheet, first row conventionally the header."
  ^bytes [rows]
  (let [baos (ByteArrayOutputStream.)]
    (with-open [z (ZipOutputStream. baos StandardCharsets/UTF_8)]
      (doseq [[nm ^String content]
              [["[Content_Types].xml" content-types]
               ["_rels/.rels" root-rels]
               ["xl/workbook.xml" workbook-xml]
               ["xl/_rels/workbook.xml.rels" workbook-rels]
               ["xl/worksheets/sheet1.xml" (sheet-xml rows)]]]
        (.putNextEntry z (ZipEntry. ^String nm))
        (.write z (.getBytes content StandardCharsets/UTF_8))
        (.closeEntry z)))
    (.toByteArray baos)))

;; ── the reader ──────────────────────────────────────────────────────

(def ^:private read-cap
  "Total uncompressed bytes a workbook may unfold to — far above any
  honest worksheet, refused loudly rather than decompressed quietly."
  (* 64 1024 1024))

(defn- unzip
  "entry name → bytes for the parts the reader consults; a workbook
  unfolding past the cap throws."
  [^bytes b]
  (with-open [z (ZipInputStream. (ByteArrayInputStream. b))]
    (loop [acc {} total 0]
      (if-some [e (.getNextEntry z)]
        (let [nm (.getName e)]
          (if (or (= nm "xl/workbook.xml")
                  (= nm "xl/_rels/workbook.xml.rels")
                  (= nm "xl/sharedStrings.xml")
                  (str/starts-with? nm "xl/worksheets/"))
            (let [out (ByteArrayOutputStream.)
                  buf (byte-array 8192)
                  n (loop [n total]
                      (let [r (.read z buf)]
                        (if (neg? r)
                          n
                          (let [n (+ n r)]
                            (when (> n read-cap)
                              (throw (ex-info "workbook unfolds past the read cap" {})))
                            (.write out buf 0 r)
                            (recur n)))))]
              (recur (assoc acc nm (.toByteArray out)) (long n)))
            (recur acc total)))
        acc))))

(defn- parse-xml ^Document [^bytes b]
  (let [f (doto (DocumentBuilderFactory/newInstance)
            (.setNamespaceAware true)
            (.setFeature XMLConstants/FEATURE_SECURE_PROCESSING true)
            (.setFeature "http://apache.org/xml/features/disallow-doctype-decl" true)
            (.setXIncludeAware false)
            (.setExpandEntityReferences false))]
    (.parse (.newDocumentBuilder f) (ByteArrayInputStream. b))))

(defn- elements [^Element el local-name]
  (let [nl ^NodeList (.getElementsByTagNameNS el "*" local-name)]
    (into [] (map #(.item nl %)) (range (.getLength nl)))))

(defn- text-of ^String [^Node n]
  (.getTextContent n))

(defn- shared-strings [parts]
  (if-some [^bytes b (get parts "xl/sharedStrings.xml")]
    (mapv (fn [^Element si] (str/join (map text-of (elements si "t"))))
          (elements (.getDocumentElement (parse-xml b)) "si"))
    []))

(defn- first-sheet-part
  "The workbook's first declared sheet, through its rels — Excel
  re-saves may rename sheet1.xml; the rels stay the truth."
  [parts]
  (or (when-some [^bytes wb (get parts "xl/workbook.xml")]
        (when-some [^bytes rels (get parts "xl/_rels/workbook.xml.rels")]
          (let [^Element sheet (first (elements (.getDocumentElement (parse-xml wb))
                                                "sheet"))
                rid (some-> sheet
                            (.getAttributeNS
                             "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
                             "id"))
                target (some (fn [^Element r]
                               (when (= rid (.getAttribute r "Id"))
                                 (.getAttribute r "Target")))
                             (elements (.getDocumentElement (parse-xml rels))
                                       "Relationship"))]
            (when target
              (get parts (if (str/starts-with? target "/")
                           (subs target 1)
                           (str "xl/" target)))))))
      (get parts "xl/worksheets/sheet1.xml")))

(defn- cell-value [^Element c shared]
  (let [t (.getAttribute c "t")
        v (some-> ^Element (first (elements c "v")) text-of)]
    (case t
      "inlineStr" (some-> ^Element (first (elements c "is")) text-of)
      "s" (when v (get shared (long (Double/parseDouble v))))
      "str" v
      "b" (when v (= "1" (str/trim v)))
      "e" nil
      (when-not (str/blank? (str v))
        (Double/parseDouble (str/trim v))))))

(defn read-sheet
  "xlsx bytes → the first sheet as a vector of row vectors, cells
  nil | String | Double | Boolean, positioned by their cell refs
  (Excel omits blank cells and rows; gaps stay honest nils)."
  [^bytes b]
  (let [parts (unzip b)
        sheet (or (first-sheet-part parts)
                  (throw (ex-info "not an xlsx workbook — no worksheet part" {})))
        shared (shared-strings parts)
        rows (elements (.getDocumentElement (parse-xml sheet)) "row")
        cells (for [^Element row rows
                    :let [row-i (dec (Long/parseLong (.getAttribute row "r")))]
                    ^Element c (elements row "c")
                    :let [v (cell-value c shared)]
                    :when (some? v)]
                [row-i (dec (col-index (.getAttribute c "r"))) v])
        n-rows (if (seq cells) (inc (long (reduce max (map first cells)))) 0)
        n-cols (if (seq cells) (inc (long (reduce max (map second cells)))) 0)]
    (reduce (fn [acc [r c v]] (assoc-in acc [r c] v))
            (vec (repeat n-rows (vec (repeat n-cols nil))))
            cells)))

;; ── the date boundary ───────────────────────────────────────────────

(def ^:private ^LocalDate excel-epoch (LocalDate/of 1899 12 30))

(defn serial->instant-str
  "An Excel date serial (days since 1899-12-30, fraction the time of
  day) → its ISO instant string, UTC — for columns the caller KNOWS
  are temporal; the codec never guesses."
  ^String [^double serial]
  (let [days (long (Math/floor serial))
        frac (- serial days)
        midnight (-> (.plusDays excel-epoch days)
                     (.atStartOfDay ZoneOffset/UTC)
                     (.toInstant))]
    (str (.plus midnight (Duration/ofMillis
                          (Math/round (* frac 86400000.0)))))))
