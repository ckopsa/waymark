(ns waymark10.server.authority-purposes
  "The :purpose of the authority kinds whose namespaces sit at their
  ns-size ceiling (ticket be0ad60e): member, grant, approval_request
  and held_call, and the notice kinds held_calls also declares,
  notifier and notice_rule (ticket 28f8ce8a). Prose, never law — declaration/check-purpose! judges
  the shape where each defresource reads it. Requires nothing, so any
  namespace may require it.")

(def member
  {:what "One principal on the house's roster: who someone is and how they sign in, and not what they may do."
   :when "Use it to invite, name or retire a person or an agent; what that principal may do is its grant's question."
   :not [{:kind :person
          :why "A person is the house's record of someone in its life; a member is a principal that signs in."}
         {:kind :grant
          :why "A grant says what a principal may do; a member says only who it is."}]})

(def grant
  {:what "The scope one principal works under, enforced by concealment: a kind it was not granted is absent, not refused."
   :when "Read it to know what a principal may see and do; a seat's grant carries no scope, because the seat row is read on each request."
   :not [{:kind :approval_request
          :why "An approval request asks for a grant to widen; the grant is the authority once it is given."}]})

(def approval-request
  {:what "The one way a principal widens its own grant: an ask for more scope that a person approves or declines."
   :when "Use it when a kind or power you need is absent; with grant_id it widens the grant you wear, without one a replacement is minted."
   :not [{:kind :seat
          :why "A seat's grant is not widened by an ask; restate the seat to change what it may do."}
         {:kind :held_call
          :why "A held call is one act waiting on a tap; an approval request asks for standing authority."}]})

(def held-call
  {:what "One act waiting on a person's tap; the row is both the notice that asks and the record of the answer."
   :when "Read it to see what waits on a person, or to allow or deny it; nobody allows their own call."
   :not [{:kind :approval_request
          :why "An approval request asks for standing authority; a held call is one act, allowed once."}]})

(def notifier
  {:what "How the engine tells a person: a server, a tool on it, an input template and the transitions it hears."
   :when "A person makes one to be told, in their own chat or mail, with a link to the row, when a row they care about moves."
   :not [{:kind :notice_rule
          :why "A notifier tells the one person its own row names; a notice rule tells whoever the moved row names."}
         {:kind :subscription
          :why "A subscription sends the log to a URL or wakes a seat; a notifier tells a person in words."}]})

(def notice-rule
  {:what "When the engine tells a person: a kind's transitions, and the ref on the moved row that names who hears of them."
   :when "Use it to tell whoever a row names, its owner or assignee, when it moves; that member's own notifier carries the send."
   :not [{:kind :notifier
          :why "A notifier says how a message is sent; a notice rule says when and to whom."}]})
