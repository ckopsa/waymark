(ns waymark10.evidence
  "`:evidence` (docs/spec-documents.md §3): a door names the documents
  it waits for, and the engine writes the guards that say so. A door's
  `:evidence {field opts}` names :waymark/document fields of its own
  kind — or, with `:via ref`, of the row that ref names — and `:min n`
  overrides the field's own count for this door. On the door the
  registry appends one tree per entry, named from the field,
  `<field>-is-filed`:

    (and (>= (count (data :photos)) 2)
         (every [a (data :photos)] (= (follow :attachment a :state) \"stored\")))

  A field that holds one document asks only that it be stored. The
  refusal names what is missing, a `cond` choosing among three
  sentences: \"This job needs 2 photos to be completed; it has 1.\" /
  \"The note cannot be signed: Ana Ruiz's consent form is not on file.\"
  / \"… consent form is still uploading.\" Its remedy is the field's own
  edit door, with the field named. A follow no read answers is unknown,
  and the door pending. The type a file must be is its field's schema
  check's, at the write (§2).

  Derived at assembly beside :occupies; the trees fingerprint as any
  authored guard. A kind's `:explain {guard-name \"sentence\"}` replaces
  a derived guard's sentence."
  (:require [clojure.string :as str]
            [waymark10.declare :as d]
            [waymark10.schema :as schema]
            [waymark10.types :as t]))

(set! *warn-on-reflection* true)

(def ^:private entry-keys #{:via :min})

(def ^:private name-fields
  "The fields a :via row is named by in a sentence, the first it has."
  [:name :display :full_name :title])

(defn- fail [kind msg]
  (throw (t/definition-error (str (name kind) " [evidence] " msg) {:check :evidence})))

(defn- word
  "A field, kind or door as a sentence says it: :patient_id → \"patient\"."
  [k]
  (str/replace (str/replace (name k) #"_id$" "") "_" " "))

(defn guard-name
  "A derived guard's name: :consent_form → :consent-form-is-filed."
  [field]
  (keyword (str (str/replace (name field) "_" "-") "-is-filed")))

(defn derives?
  "Whether a door of `r` derives the guard `gname` from its :evidence."
  [r gname]
  (boolean (some (fn [[_ a]]
                   (let [ev (:evidence a)]
                     (when (map? ev) (some #(= gname (guard-name %)) (keys ev)))))
                 (:actions r))))

(defn- taken
  "[to-be cannot]: how the sentences say the door is taken — :complete
  is \"to be completed\" and \"cannot be completed\"; a door of several
  words is named whole."
  [door]
  (let [w (word door)]
    (if (str/includes? w " ")
      [(str "before " w) (str "is not ready for " w)]
      (let [p (if (str/ends-with? w "e") (str w "d") (str w "ed"))]
        [(str "to be " p) (str "cannot be " p)]))))

(defn- document-field
  "{:min :listed} of `field` on `r`, or nil when it is no document."
  [r field]
  (let [em (when (:schema r) (schema/entry-map (:schema r)))]
    (when-some [doc (get-in em [field :properties :waymark/document])]
      {:min (:min doc 1)
       :listed (boolean (some #(and (= field (:field %)) (:listed %))
                              (schema/ref-fields (:schema r))))})))

(defn- ref-kind
  "The kind one ref field of `r`, not a list, names; nil when it is none."
  [r field]
  (some #(when (and (= field (:field %)) (not (:listed %))) (:kind %))
        (when (:schema r) (schema/ref-fields (:schema r)))))

(defn- edit-door
  "The first :edit door of `r`, by name, whose input carries `field`."
  [r field]
  (some (fn [[a ad]]
          (when (and (:edit ad)
                     (some #{field} (schema/entry-keys (or (:input ad) (:schema r)))))
            a))
        (sort-by key (:actions r))))

(defn- entry
  "One :evidence entry of `door` on `kind`, judged against the registry."
  [kinds kind r door field opts]
  (let [at (str (name door) " :evidence " (pr-str field))]
    (when-not (or (nil? opts) (map? opts))
      (fail kind (str at " is {:via ref :min n}; " (pr-str opts) " is not")))
    (when-some [extra (seq (remove entry-keys (keys opts)))]
      (fail kind (str at " declares " (vec extra) ", keys it does not know")))
    (let [via (:via opts)
          owner (if (nil? via)
                  kind
                  (or (ref-kind r via)
                      (fail kind (str at " :via " (pr-str via) " is not a ref field naming its :kind"))))
          orow (or (get kinds owner)
                   (fail kind (str at " reads " (name owner) ", a kind this registry does not hold")))
          doc (or (document-field orow field)
                  (fail kind (str at " is not a :waymark/document field of " (name owner))))
          n (:min opts (:min doc))]
      (when-not (pos-int? n)
        (fail kind (str at " :min is a positive count, not " (pr-str n))))
      (when (and (not (:listed doc)) (< 1 n))
        (fail kind (str at " holds one document; :min " n " cannot be met")))
      {:kind kind :door door :field field :via via :owner owner :min n
       :listed (:listed doc)
       :edit (edit-door orow field)
       :named-by (when via (some (set (schema/entry-keys (:schema orow))) name-fields))})))

(defn- held
  "The field's value as the door reads it: the row's own, or the :via row's."
  [{:keys [field via owner]}]
  (if via (list 'follow owner (list 'data via) field) (list 'data field)))

(defn- state-of [ref] (list 'follow :attachment ref :state))

(defn- str-form
  "(str …) of `parts`, its neighbouring literals joined into one."
  [& parts]
  (cons 'str (reduce (fn [acc p]
                       (if (and (string? p) (string? (peek acc)))
                         (conj (pop acc) (str (peek acc) p))
                         (conj acc p)))
                     []
                     (remove #(= "" %) parts))))

(defn- filed-form
  "The tree: at least :min attachments, every one of them stored."
  [{:keys [listed min] :as e}]
  (let [h (held e)]
    (if listed
      (list 'and
            (list '>= (list 'count h) min)
            (list 'every ['a h] (list '= (state-of 'a) "stored")))
      (list '= (state-of h) "stored"))))

(defn- whose
  "Whose document the sentence names: its, or the :via row's by name."
  [{:keys [via owner named-by]}]
  (if (nil? via)
    "its"
    (let [plain (str "the " (word via) "'s")]
      (if (nil? named-by)
        plain
        (let [n (list 'follow owner (list 'data via) named-by)]
          (list 'cond (list 'is-set n) (str-form n "'s") :else plain))))))

(defn- missing-form
  "The (cond …) that says which document is missing, and how."
  [{:keys [kind door field via listed min] :as e}]
  (let [h (held e)
        [to-be cannot] (taken door)
        kw (word kind)
        fw (word field)
        who (whose e)
        lead (str "The " kw " " cannot ": ")]
    (if listed
      (list 'cond
            (list '< (list 'count h) min)
            (if via
              (str-form "This " kw " needs " (str min) " of " who " " fw " " to-be
                        "; it has " (list 'count h) ".")
              (str-form "This " kw " needs " (str min) " " fw " " to-be
                        "; it has " (list 'count h) "."))
            (list 'some ['a h] (list '= (state-of 'a) "pending"))
            (str-form lead who " " fw " are still uploading.")
            :else
            (str-form lead who " " fw " are not all on file."))
      (list 'cond
            (list '= (state-of h) "pending")
            (str-form lead who " " fw " is still uploading.")
            :else
            (str-form lead who " " fw " is not on file.")))))

(defn- filed-guard [kinds e]
  (let [gname (guard-name (:field e))
        remedy (when-some [door (:edit e)]
                 (cond-> {:door (keyword (name (:owner e)) (name door)) :fields [(:field e)]}
                   (:via e) (assoc :id (list 'data (:via e)))))]
    (d/sentence-guard gname
                      (d/refuse (or (get-in kinds [(:kind e) :explain gname]) "{missing}"))
                      (filed-form e)
                      (cond-> {:vars {:missing (missing-form e)}}
                        remedy (assoc :remedies [remedy])))))

(defn- add-guard
  "`g` on door `door` of `r`, once: a registry built again from its own
  rdefs finds the guard already there."
  [r door g]
  (update-in r [:actions door :guards]
             (fn [gs]
               (let [gs (vec gs)]
                 (if (some #(= (:name g) (:name %)) gs) gs (conj gs g))))))

(defn derive-kinds
  "`kinds` ({kind rdef}) with every :evidence door's derived guards
  appended — the registry's step before it fingerprints. Throws the
  [evidence] definition error for an entry its kind cannot hold."
  [kinds]
  (let [entries (vec (for [[kind r] (sort-by key kinds)
                           [door a] (sort-by key (:actions r))
                           :let [ev (:evidence a)]
                           :when (some? ev)
                           [field opts] (if (and (map? ev) (seq ev))
                                          (sort-by key ev)
                                          (fail kind (str (name door) " :evidence is a map {field opts}; "
                                                          (pr-str ev) " is not")))]
                       (entry kinds kind r door field opts)))]
    (reduce (fn [ks e] (update ks (:kind e) add-guard (:door e) (filed-guard kinds e)))
            kinds
            entries)))
