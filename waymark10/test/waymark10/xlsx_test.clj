(ns waymark10.xlsx-test
  "The minimal xlsx codec: write → read round-trips values and
  positions; the reader also handles the parts Excel re-saves that
  the writer never emits (sharedStrings, gapped rows). No database."
  (:require [clojure.test :refer [deftest is testing]]
            [waymark10.server.xlsx :as xlsx])
  (:import (java.io ByteArrayOutputStream)
           (java.nio.charset StandardCharsets)
           (java.util.zip ZipEntry ZipOutputStream)))

(deftest round-trip
  (let [rows [["id" "title" "count" "ratio" "active" "note"]
              ["r-1" "Plain" 3 0.5 true nil]
              ["r-2" "Ünïcode & <tags> \"quoted\"" nil 12.25 false "x"]]
        back (xlsx/read-sheet (xlsx/write-sheet rows))]
    (is (= 3 (count back)))
    (is (= ["id" "title" "count" "ratio" "active" "note"] (first back)))
    (testing "strings, numbers, booleans, and gaps survive"
      (is (= "r-1" (get-in back [1 0])))
      (is (= 3.0 (get-in back [1 2])) "numbers read back as doubles")
      (is (= 0.5 (get-in back [1 3])))
      (is (= true (get-in back [1 4])))
      (is (nil? (get-in back [1 5])))
      (is (= "Ünïcode & <tags> \"quoted\"" (get-in back [2 1]))
          "escaping round-trips")
      (is (nil? (get-in back [2 2])))
      (is (= false (get-in back [2 4]))))))

(deftest column-letters-spell-like-a-spreadsheet
  (is (= "A" (xlsx/col-letters 0)))
  (is (= "Z" (xlsx/col-letters 25)))
  (is (= "AA" (xlsx/col-letters 26)))
  (is (= "AZ" (xlsx/col-letters 51)))
  (is (= "BA" (xlsx/col-letters 52))))

(deftest date-serials-convert-when-asked
  ;; 45838 is 2025-06-30 in Excel's 1900 system; .5 is noon
  (is (= "2025-06-30T00:00:00Z" (xlsx/serial->instant-str 45838.0)))
  (is (= "2025-06-30T12:00:00Z" (xlsx/serial->instant-str 45838.5))))

;; ── what Excel re-saves (never our writer's output) ─────────────────

(defn- zip-parts ^bytes [parts]
  (let [baos (ByteArrayOutputStream.)]
    (with-open [z (ZipOutputStream. baos StandardCharsets/UTF_8)]
      (doseq [[^String nm ^String content] parts]
        (.putNextEntry z (ZipEntry. nm))
        (.write z (.getBytes content StandardCharsets/UTF_8))
        (.closeEntry z)))
    (.toByteArray baos)))

(def ^:private resaved
  ;; sharedStrings, a renamed sheet part reached through the rels, a
  ;; skipped blank row, and a formula cache — the Excel dialect
  (zip-parts
   [["xl/workbook.xml"
     "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet name=\"Data\" sheetId=\"1\" r:id=\"rId9\"/></sheets></workbook>"]
    ["xl/_rels/workbook.xml.rels"
     "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId9\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/renamed.xml\"/></Relationships>"]
    ["xl/sharedStrings.xml"
     "<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><si><t>shared one</t></si><si><r><t>rich </t></r><r><t>text</t></r></si></sst>"]
    ["xl/worksheets/renamed.xml"
     "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData><row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c><c r=\"C1\" t=\"s\"><v>1</v></c></row><row r=\"3\"><c r=\"B3\"><v>42</v></c><c r=\"C3\" t=\"str\"><v>cached</v></c><c r=\"D3\" t=\"b\"><v>1</v></c></row></sheetData></worksheet>"]]))

(deftest reads-the-excel-dialect
  (let [rows (xlsx/read-sheet resaved)]
    (is (= "shared one" (get-in rows [0 0])))
    (is (= "rich text" (get-in rows [0 2])) "rich-text runs concatenate")
    (is (= [nil nil nil nil] (get rows 1)) "a skipped row is honest nils")
    (is (= 42.0 (get-in rows [2 1])))
    (is (= "cached" (get-in rows [2 2])) "formula caches read as their value")
    (is (= true (get-in rows [2 3])))))

(deftest doctypes-are-refused
  (let [evil (zip-parts
              [["xl/worksheets/sheet1.xml"
                "<!DOCTYPE foo [<!ENTITY xxe SYSTEM \"file:///etc/hosts\">]><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData/></worksheet>"]])]
    (is (thrown? Exception (xlsx/read-sheet evil)))))
