(ns waymark10.server.ui-assembly
  "The generic UI page (GET /api/-/ui), assembled from ordered source
  fragments under resources/waymark10/ui/. The fragments are a straight
  partition of the old single-file ui.html: concatenating them in
  `fragments` order reproduces one self-contained page — one <style>
  block, one <script> block, the same flat JS scope and load order.
  The numeric filename prefixes mirror the order, but THIS vector is
  authoritative; the classpath directory is never listed."
  (:require [clojure.java.io :as io]))

(def fragments
  ["waymark10/ui/010-head.html"        ; doctype + <head>, opens <style>
   "waymark10/ui/020-base.css"
   "waymark10/ui/030-screens.css"
   "waymark10/ui/040-mobile.css"
   "waymark10/ui/050-shell.html"       ; closes </style>, body markup, opens <script>
   "waymark10/ui/100-core.js"
   "waymark10/ui/110-discovery-routing.js"
   "waymark10/ui/120-nav-home.js"
   "waymark10/ui/130-collection.js"
   "waymark10/ui/134-feed.js"
   "waymark10/ui/140-links-access.js"
   "waymark10/ui/150-values-parts.js"
   "waymark10/ui/160-resource-surface.js"
   "waymark10/ui/170-forms.js"
   "waymark10/ui/180-action-dialog.js"
   "waymark10/ui/190-worksheet-attachments.js"
   "waymark10/ui/200-events-follow.js"
   "waymark10/ui/210-ledger.js"
   "waymark10/ui/220-boot.js"
   "waymark10/ui/900-tail.html"])      ; </script></body></html>

(defn- fragment [path]
  (or (some-> (io/resource path) slurp)
      (throw (ex-info (str "UI fragment missing from classpath: " path)
                      {:fragment path}))))

(defn assemble
  "The full page as one string. Called once, at handler construction —
  a missing fragment fails the engine's startup, never a request."
  []
  (apply str (map fragment fragments)))
