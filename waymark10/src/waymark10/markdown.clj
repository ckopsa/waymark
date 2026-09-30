(ns waymark10.markdown
  "One safe markdown renderer for the prose fields.

  CommonMark, plus GFM tables, task lists and fenced code, and nothing
  a writer can make run: raw HTML is escaped as text, a link keeps its
  href only for http, https and mailto and rides rel=\"noopener
  noreferrer\", and an image renders as a link to its address. A soft
  line break stays a line break. The stored text is never rewritten:
  this is read-side only, reached through the render door
  (waymark10.server.routes.ui), so the API and MCP answers for a prose
  field stay the bytes that were written."
  (:import (java.util Map)
           (org.commonmark.ext.gfm.tables TablesExtension)
           (org.commonmark.ext.task.list.items TaskListItemsExtension)
           (org.commonmark.node Image Link Node Text)
           (org.commonmark.parser Parser)
           (org.commonmark.renderer.html AttributeProvider
                                         AttributeProviderFactory
                                         DefaultUrlSanitizer HtmlRenderer)))

(set! *warn-on-reflection* true)

(def ^:private extensions
  [(TablesExtension/create) (TaskListItemsExtension/create)])

(def ^:private ^Parser parser
  (-> (Parser/builder)
      (.extensions extensions)
      (.build)))

(def ^:private link-rel
  "Every link opens with no window.opener and no referrer."
  (reify AttributeProviderFactory
    (create [_ _]
      (reify AttributeProvider
        (setAttributes [_ node _ attrs]
          (when (instance? Link node)
            (.put ^Map attrs "rel" "noopener noreferrer")))))))

(def ^:private ^HtmlRenderer renderer
  (-> (HtmlRenderer/builder)
      (.extensions extensions)
      (.escapeHtml true)
      (.sanitizeUrls true)
      (.urlSanitizer (DefaultUrlSanitizer. ["http" "https" "mailto"]))
      (.attributeProviderFactory link-rel)
      (.softbreak "<br />\n")
      (.build)))

(defn- children [^Node n]
  (take-while some? (iterate (fn [^Node c] (when c (.getNext c)))
                             (.getFirstChild n))))

(defn- nodes
  "`n` and everything under it, in document order, realized before any
  of them is moved."
  [^Node n]
  (loop [todo (list n) out []]
    (if-let [x (first todo)]
      (recur (into (rest todo) (reverse (children x)))
             (conj out x))
      out)))

(defn- image->link!
  "An image becomes a link to its address, its alt text the link's
  text: no picture is ever fetched from where a writer points."
  [^Image img]
  (let [link (Link. (.getDestination img) (.getTitle img))]
    (doseq [^Node c (vec (children img))]
      (.appendChild link c))
    (when-not (.getFirstChild link)
      (.appendChild link (Text. (.getDestination img))))
    (.insertBefore img link)
    (.unlink img)))

(defn render
  "One text's HTML. nil renders as the empty string."
  ^String [text]
  (let [doc (.parse parser (str text))]
    (run! image->link! (filter #(instance? Image %) (nodes doc)))
    (.render renderer doc)))
