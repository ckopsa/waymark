(ns workqueue10.resources.thread
  "The Thread resource: the household's CONVERSATIONS as addresses —
  one row per chat, from Telegram and from the phone's texts, over
  its own confluence (docs/spec-threads.md).

  THE GAP IT CLOSES IS ADDRESSES, NOT MESSAGES. A fact found in a
  chat had nowhere to point: `cites-what-it-claims` means an insight
  cannot carry it, so the commitments probe anchored on a person, the
  composer named the source in prose, and the driver GUESSED which
  thread to read — it picked one 1:1 conversation out of ten, and the
  group carrying an unanswered birthday invitation was never opened.
  With this kind an insight cites /api/threads/<id>, the driver picks
  from rows instead of a heuristic, and a thread whose last word
  moved is an arrival.

  MIRROR THE THREAD, NEVER THE MESSAGES — flickr's works-not-files
  line, drawn one domain over. What is here is timestamps, counts and
  names. What is NOT here, by decision and not omission: bodies,
  previews, snippets, and unread counts. The first three are what was
  said. The fourth is a fact about the owner's PHONE rather than the
  conversation — it moves when he opens the app and nobody spoke, so
  it would churn every row on every pass, make \"this thread moved\"
  ambiguous, and publish to every grant-holder how much the house has
  left unanswered. Bodies stay behind Gate, under a capability a
  person approved.

  A THREAD IS NEVER DONE. There is no state a conversation reaches
  where the household has FINISHED it, so :over declares an empty
  :accomplished beside :let-go #{\"dropped\"} — this kind has
  endings, and none of them is a deed. A thread the rig stops listing
  is `dropped` (:on-gone), which keeps the row serving: the record
  stands, the sync machine renders its staleness, and the address an
  old insight cites is still an address.

  PULL-ONLY, structurally. There is no :push-on-write, no local
  writes and no domain actions — and the seam under it, ThreadSource,
  has no push method at all. The queue mirrors the house's
  conversations; it does not write them, and saying something in one
  is a leashed capability a person approves at Gate's own door, never
  a sync pass's business. This is task_list's posture with the reason
  restated.

  PARTICIPANTS ARE THE ASSIGNEE PATTERN, PLURAL. :participant_names
  is the rig's own words, whole and always; :participants is its
  resolvable projection — the framework's :many external-keyed ref,
  matched on person's :name. A name the roster does not hold is
  written down `observed` by the source's birth-fn, so the roster
  grows on its own and nobody is told who their people are. A name
  the ref cannot resolve leaves a gap that renders, the sentence
  :assignee earned.

  :nav :secondary and no population, for `value`'s reason: a
  conversation is not a thing to DO, and a permanently-open row on a
  primary kind would card in do-now forever. What reads this kind is
  the sitting driver's thread selection and the commitments probe's
  evidence address.

  RECORDED GAPS, all healable without a change here — and one of
  them healed exactly that way: :last_message_at was nil for every
  messa row, because that rig answered an empty time for every
  thread; the rig learned to read its own timestamp on 2026-08-28 and
  the field filled with no change in this file. What is still open:
  :message_count_window is nil at both wired rigs, because the only
  way to count messages is to read them; messa still answers no time
  for an individual MESSAGE; and a tgram GROUP mirrors with no
  participants, because the listing exposes none and this source will
  not read a group's messages to infer them."
  (:require [waymark10.dsl :refer [resource]]
            [waymark10.server.mirror :as mirror]))

;; a conversation moves at human cadence, and the listing read is one
;; call at either rig — the queue can be as slow here as it is about
;; the lists the work lives in
(def ttl-seconds 900)
(def discover-every 900)

(defn thread-resource
  [adapter]
  (resource
   (mirror/declaration
    {:kind :thread
     :plural "threads"
     ;; not a thing to do — see the ns docstring
     :nav :secondary
     ;; a conversation has endings and none of them is a deed: the
     ;; empty :accomplished is the honest half of this declaration
     :over {:field :status :accomplished #{} :let-go #{"dropped"}}
     :summary "{data.title} · {data.source}"
     :label-template "{data.title}"
     :display {:title "{data.title}"}
     :schema
     [:map
      ;; the household's own word for the chat — and at messa a GROUP
      ;; title is a roster rather than a headline ("Amy Shumway,
      ;; Calista Shumway, …"), which is why this is longer than a
      ;; title has any right to be
      ;; :raw because it is a LABEL and not prose — long, but one line:
      ;; the rig's own spelling of who is in the conversation
      [:title {:optional true
               :x-display {:raw true
                           :label "What the conversation is called"
                           :help "The name the phone shows for this chat — a person's name for a one-to-one thread, everybody's names for a group."}}
       [:maybe [:string {:max 400}]]]
      ;; the confluence's routing tag — which rig this row drinks
      ;; from; the enum is the tag set main wires
      [:source {:optional true :filter #{:eq :in}
                :x-display
                {:label "Where the conversation lives"
                 :choices {"tgram" "Telegram"
                           "messa" "Text messages on the phone"}}}
       [:maybe [:enum "tgram" "messa"]]]
      ;; :chat_kind and not :kind — `kind` is the row envelope's own
      ;; word, and a data field wearing it reads as the row's type
      ;; everywhere a card is rendered
      [:chat_kind {:optional true :filter #{:eq :in}
                   :x-display
                   {:showcase true
                    :label "One to one, or a group"
                    :help "Whether this thread is between two people or several."
                    :choices {"direct" "One to one — this house and one other person"
                              "group" "A group — several people in one thread"}}}
       [:maybe [:enum "direct" "group"]]]
      ;; the lifecycle, as data (the mirror rule): live, or let go
      [:status {:optional true :filter #{:eq :in}
                :x-display
                {:showcase true
                 :label "Still listed"
                 :help "Live while the rig still carries this conversation; dropped once it stops answering for it — the row stands either way."
                 :choices {"live" "Live — the rig still lists this conversation"
                           "dropped" "Dropped — the rig no longer lists it; the record stands"}}}
       [:maybe [:enum "live" "dropped"]]]
      ;; THE CURSOR THAT MATTERS. Neither rig takes a `since=`, so the
      ;; listing is the window and this is what the DRIVER windows on:
      ;; a thread whose last word moved since the watermark is an
      ;; arrival. Both rigs speak a clock since 2026-08-28; nil is
      ;; still reachable, and still means the rig read no time rather
      ;; than that nothing was said.
      [:last_message_at {:optional true :sort :default
                         :x-display
                         {:showcase true
                          :label "When something was last said"
                          :help "The rig's own timestamp for the last message. A rig that could not read a time leaves this empty rather than guessing one."}}
       [:maybe :waymark/instant]]
      ;; declared and nil at both wired rigs: the only way to count
      ;; messages is to READ them, which is the thing this kind exists
      ;; not to do. Declared anyway because an email-folder source
      ;; answers a count for free, and a field added later is a
      ;; migration where a field left nil is a sentence.
      [:message_count_window {:optional true
                              :x-display
                              {:label "Messages in the window"
                               :help "How many messages the rig counted in the window it answered for. Neither wired rig counts, so this is normally empty."}}
       [:maybe [:int {:min 0}]]]
      ;; the rig's own words, whole and always — the raw-text-beside-
      ;; resolvable-ref pattern, plural
      [:participant_names {:optional true
                           :x-display
                           {:label "Who is in it, as the rig names them"
                            :help "The names exactly as the phone spells them — including the ones that are phone numbers rather than people."}}
       [:maybe [:vector [:string {:max 120}]]]]
      ;; …and its resolvable projection: the framework's :many
      ;; external-keyed ref, matched on the roster's own :name. The
      ;; names above stay whole; this is the subset the house can name.
      [:participants {:optional true
                      :kind :person
                      :external-key :participant_names
                      :match :name
                      :x-display
                      {:label "Who is in it"
                       :help "The people on the roster these names resolve to. A name nobody is written down for simply leaves a gap."}}
       [:maybe [:vector :waymark/ref]]]]
     :filterable {:state #{:eq :in}}}
    {:adapter adapter
     :ttl-seconds ttl-seconds
     :discover-every discover-every
     ;; :whole, and it is honest: every field comes from one listing
     ;; entry and there are no hub-local words on this kind at all, so
     ;; absence really is unset rather than silence. (The ref is
     ;; engine-maintained and excluded from the replace by the
     ;; framework's own rule.)
     :document :whole
     ;; the rig ANSWERED and the thread was absent from its listing:
     ;; the house stopped talking there, or the window rolled past.
     ;; Either way the row keeps serving — nothing is deleted, and the
     ;; address an old insight cites is still an address.
     :on-gone {:set {:status "dropped"}}
     ;; the cadenced whole-kind heal — one listing read per pass per
     ;; rig, so a dropped thread and a renamed group land within the
     ;; hour rather than at the next boot. It is also what re-resolves
     ;; the participant refs against a roster that grew since.
     :resync-every 3600})))
