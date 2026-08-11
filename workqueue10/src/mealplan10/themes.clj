(ns mealplan10.themes
  "The weekly theme calendar: our meal-plan week runs Tuesday to
  Tuesday.

  Every weekday has a fixed theme except Sunday, which draws from the
  dynamic rotation resource (mealplan10.resources.rotation). Saturday
  owns BBQ, which is why Wednesday's \"american\" explicitly excludes
  it."
  (:import (java.time DayOfWeek LocalDate)))

(def rotating
  "Sunday's placeholder until a theme is picked."
  "rotating")

(def weekday-themes
  {DayOfWeek/MONDAY    "italian"
   DayOfWeek/TUESDAY   "mexican"    ; Taco Tuesday — the week starts here
   DayOfWeek/WEDNESDAY "american"   ; American food, but no BBQ (Saturday's job)
   DayOfWeek/THURSDAY  "asian"
   DayOfWeek/FRIDAY    "pizza"
   DayOfWeek/SATURDAY  "bbq"
   DayOfWeek/SUNDAY    rotating})

(defn weekday-theme [^LocalDate date]
  (get weekday-themes (.getDayOfWeek date)))

(def theme-labels
  {"mexican"  "Taco Tuesday (Mexican/Latin American)"
   "american" "American (no BBQ)"
   "asian"    "Asian"
   "pizza"    "Pizza"
   "bbq"      "BBQ"
   "italian"  "Italian"
   rotating   "Sunday special (pick from the rotation)"})
