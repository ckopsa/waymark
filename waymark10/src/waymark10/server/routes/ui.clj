(ns waymark10.server.routes.ui
  "The generic UI's routes: the root, its back-compat spelling, and
  the lite page.

  A module's route set, mounted through waymark10.modules — the
  router knows this namespace by nothing but the vector it answers
  with. THE ROOT LIVES HERE, and that is a deliberate reading of the
  core line: `/` is not an address core has anything to say at; it is
  the UI asset's canonical URL and nothing else. An engine assembled
  without the generic UI has no page to serve there, and a 404 is the
  honest answer — the same answer this module already gives when the
  asset is off the classpath."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [waymark10.server.problems :as p]
            [waymark10.server.router :as router]
            [waymark10.server.ui-assembly :as ui-assembly]))

(set! *warn-on-reflection* true)

(defn- mobile-ua?
  "A phone-shaped User-Agent. `Mobi` is the token every mobile
  browser ships (Android Chrome, iOS Safari, Firefox Mobile);
  iPad/Android keep tablets in the net."
  [req]
  (boolean (re-find #"(?i)mobi|android|iphone|ipad"
                    (get-in req [:headers "user-agent"] ""))))

(defn- ui-page
  "GET / and /api/-/ui (and /api/-/ui-lite): the envelope-driven
  generic UI — the root is its canonical address; /api/-/ui stays
  as the back-compat spelling existing deep links (source_ui_href,
  bookmarks) already carry. One self-contained page (vanilla JS, no
  external hosts) that renders whatever the wire declares: kinds
  from well-known,
  collections from the query grammar, envelopes as forms. A static
  asset, served to anyone — a scoped request's DATA stays projected
  by the API it drives. The full client (the waymark9 generic UI,
  ported to wire 10) assembles from resources/waymark10/ui/;
  ui_lite.html preserves the original phase-10 page.

  A mobile User-Agent gets the SAME page stamped <html data-ui=
  \"mobile\"> — one client, two shells; the page's own CSS/JS key the
  mobile chrome (bottom tab nav, card rows, sheet dialogs) off the
  stamp. ?ui=mobile|desktop overrides the sniff, and the page's ⋯
  menu links the switch.

  Takes the page as a STRING (or nil → 404) — the full client arrives
  pre-assembled from fragments by ui-assembly/assemble; ui_lite.html
  is still slurped whole at the call site."
  [_eng page]
  (let [mobile (some-> page
                       (str/replace-first
                        "<html lang=\"en\">"
                        "<html lang=\"en\" data-ui=\"mobile\">"))]
    (fn [req]
      (if page
        {:status 200
         :headers {"Content-Type" "text/html; charset=utf-8"}
         :body (if (case (get (router/query-params req) "ui")
                     "mobile"  true
                     "desktop" false
                     (mobile-ua? req))
                 mobile
                 page)}
        (throw (p/problem :not-found 404 "Not found"
                          {:detail "The UI asset is not on the classpath."}))))))

(defn routes
  "Three static addresses for one page: the page assembles ONCE here,
  as it always did, and the root and /api/-/ui share the very same
  handler."
  [eng]
  (let [ui (ui-page eng (ui-assembly/assemble))]
    {:module :ui
     :static [["/" {:get ui}]
              ["/api/-/ui" {:get ui}]
              ["/api/-/ui-lite"
               {:get (ui-page eng (some-> (io/resource "waymark10/ui_lite.html")
                                          slurp))}]]}))
