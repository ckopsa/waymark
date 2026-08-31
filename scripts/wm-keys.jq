# THE WORDS A SUBJECT IS SEARCHED BY (waymark-jux), and the words a
# value and a subject are compared on. Lifted out of sitting-run.sh on
# 2026-08-30 (waymark-frv) so WHAT THE HOUSE ALREADY SAYS can name a
# Gate search key in the SAME vocabulary the work orders use, and so a
# fixture can run both over literal JSON with no house at all. The
# driver reads this file into $JQ_KEYS and prepends it to every program
# that needs it; `scripts/house-says.jq` is concatenated after it the
# same way.
  def wm_stop: ["a","an","and","are","as","at","be","but","by","did","do",
                "does","for","from","get","got","had","has","have","he",
                "her","him","his","how","i","if","in","into","is","it",
                "its","me","my","no","not","of","off","on","or","our",
                "out","over","per","she","so","that","the","their","them",
                "then","they","this","to","up","us","via","was","we",
                "were","what","when","where","which","who","why","will",
                "with","would","you","your"];
  def wm_generic: ["breakfast","brunch","lunch","dinner","supper","coffee",
                   "tea","drinks","meeting","meet","call","zoom",
                   "appointment","appt","party","birthday","anniversary",
                   "visit","trip","event","reminder","pickup","pick","drop",
                   "dropoff","task","todo","time","date","day","days","week",
                   "weekend","today","tomorrow","tonight","morning","evening",
                   "afternoon","night","game","practice","class","session",
                   "review","check","schedule","plan","planning","sync",
                   "standup","catchup","catch","followup","follow","update",
                   "note","notes"];
  def wm_tokens: (tostring | [ splits("[^A-Za-z0-9]+") ] | map(select(length > 0)));
  def wm_norm: (ascii_downcase | gsub("[^a-z0-9]"; ""));
  def wm_dedupe: reduce .[] as $x
    ([]; . as $acc | ($x | wm_norm) as $n
         | if ([ $acc[] | wm_norm ] | index($n)) then $acc else $acc + [$x] end);
  def wm_keys:
    wm_tokens as $t
    | (wm_stop + wm_generic) as $drop
    | ([ range(0; ($t | length))
         | {i:., w:$t[.], lc:($t[.] | wm_norm)}
         | select(.w | test("^[A-Z][A-Za-z]{2,}$"))
         | . as $c | select(($drop | index($c.lc)) == null) ]) as $caps
    | ([ $caps[] | .i ]) as $ci
    | ([ $caps[] | . as $c | select(($ci | index($c.i - 1)) != null) | .w ]) as $surnames
    | (([ $caps[] | .w ] - $surnames) | sort_by(-(length))) as $rest
    | ($surnames + $rest) as $all
    # a key of three letters or fewer is a SUBSTRING, and IMAP TEXT
    # search is substring search: "Kev" answered 248 messages — the
    # whole mailbox, dressed as material. Short keys are used only when
    # the line offers nothing longer.
    | ([ $all[] | select(length >= 4) ]) as $long
    | (if ($long | length) > 0 then $long else $all end) as $names
    | (if ($names | length) > 0 then $names
       else ([ $t[] | . as $w | select(($drop | index($w | wm_norm)) == null) ]
             | join(" ") | if (length > 0) then [.] else [] end)
       end)
    | map(select(length > 0)) | wm_dedupe | .[0:2];
  # the words a value and a subject can be compared on: normalized, four
  # letters or longer, stopwords out — "the" is not a match
  def wm_words: wm_tokens | map(wm_norm) | map(select(length >= 4))
                | [ .[] as $w | select((wm_stop | index($w)) == null) | $w ]
                | unique;
  # A value OWNS the words of its name, of every activity it loves, and
  # the LONG words of what it says. Six letters or more from `says` is
  # not fussiness: a value whose prose reads "a long healthy life … the
  # God of War game" otherwise owns "long" and "game", and matched a
  # woodworking task on "long" — prose filler is short, and the words
  # that actually name a value (woodworking, appointments, caregivers,
  # grandchildren) are long.
  def wm_value_words($v):
    (([ ($v.name // ""), (($v.loved // []) | join(" ")) ] | join(" ") | wm_words)
     + (($v.says // "") | wm_words | map(select(length >= 6))))
    | unique;
  # VALUE-FIT (waymark-jux): does any live value own a word this subject
  # says? Nothing fits is a legitimate answer, and the caller turns it
  # into a journal-only order rather than demanding an outcome that
  # would have to invent the value it serves.
  def wm_value_fit($subject; $values):
    ($subject | wm_words) as $sw
    | [ $values[] | . as $v
        | (wm_value_words($v)) as $vw
        | ([ $vw[] as $w | select(($sw | index($w)) != null) | $w ]) as $shared
        | select(($shared | length) > 0)
        | {self:$v.self, id:$v.id, name:$v.name,
           state:($v.state // "declared"),
           matched:($shared | sort_by(-(length)) | .[0])} ]
    | .[0:1];
  # A value the house has only been OBSERVED to hold is a lawful thing
  # to compose against: `outcome/names-a-value` holds observed and
  # declared alike and refuses only retired, and the crown ranks an
  # observed value LOWER rather than turning the bundle away. What it
  # must not do is pass silently as something the household said in so
  # many words — so every order that lands on one says which it is, in
  # the same sentence that names it.
  def wm_value_note($f):
    "value: " + $f.self + " (" + $f.name + ") — matched on \"" + $f.matched + "\"."
    + (if (($f.state // "declared") == "observed")
       then " That value is OBSERVED, not affirmed: the house has not said it in so many words, only its record has. Name it anyway — `names-a-value` holds an observed value, and the crown ranks it lower rather than refusing it — and say in the goal that it serves a reading of this household rather than a word the household gave."
       else "" end);
