(ns alda.parser
  (:require [instaparse.core :as insta]
            [clojure.string :as str]
            [clojure.java.io :as io]))

(declare apply-global-attributes assign-instances consolidate-instruments
         lispify-part)

(def ^:private alda-parser
  (insta/parser (io/resource "grammar/alda.bnf")))

(defn parse-input
  "Parses a string of alda code, determines which instrument instances are
   assigned to each instrument call, and returns a map of each instrument
   instance to its own parse tree of music data."
  [alda-code]
  (->> alda-code
       alda-parser
       (insta/transform {:score apply-global-attributes})
       (insta/transform {:score assign-instances})
       (insta/transform {:score consolidate-instruments})
       (map lispify-part)))

(defn- lispify-part
  "Generates Clojure code from an instrument part's Hiccup data structure."
  [[instance part]]
  (let [[instrument-name instance-number] (first instance)
        tree (vec (cons :part part))]
    (insta/transform
      {:number      #(Integer/parseInt %)
       :tie         (constantly :tie)
       :slur        (constantly :slur)
       :dots        #(hash-map :dots (count %))
       :note-length #(list* 'note-length %&)
       :duration    #(list* 'duration %&)
       :part        #(list* 'part instrument-name instance-number %&)}
      tree)))

(defn- apply-global-attributes
  "If the first node is a :global-attributes node, prepends it to the music
   data of the first instrument as an :attribute-changes node."
  [& [[first-tag & contents] & nodes :as score-contents]]
  (if (= first-tag :global-attributes)
    (let [[[_ instrument-call-node [_ & music-data]] & other-nodes] nodes
          attribute-changes-node (apply vector :attribute-changes contents)
          music-data-node (apply vector :music-data attribute-changes-node music-data)]
      (apply vector :score [:instrument instrument-call-node music-data-node]
                    other-nodes))
    (apply vector :score score-contents)))

(declare update-data)

(defn- assign-instances
  "Reconstructs the parse tree by going through the score linearly and deducing
   what specific instrument 'instances' are referred to by each instrument call.
   In the resulting parse tree, each :instrument node has a :tracks node that
   lists these instrument instances, e.g. [:tracks {'guitar' 1} {'bass' 1}],
   rather than an :instrument-call node.

   A new numbered instance of an instrument is created whenever a stock instrument
   is called with a nickname, either as part of a group or not.

   e.g. If there is already a clarinet 1 and there is already a cello 1 nicknamed
   'thor', then this -- thor/clarinet 'band': -- will refer to the same instance
   of cello (cello 1, 'thor'), but a new clarinet instance (clarinet 2) because
   a nickname, 'band' is being given to this group, and 'clarinet' refers to the
   stock instrument, not any particular named instance of clarinet.

   On the other hand, -- thor/clarinet: -- in the same scenario would refer to
   cello 1 and clarinet 1, the same instances that were already in use."
  [& instrument-nodes]
  (-> (reduce update-data {:table {}, :nicknames {}, :score [:score]}
                          instrument-nodes)
      :score))

(defn- assign
  "Assigns instance(s) to a name-node. Returns nil for nickname nodes.

   nickname is nickname of the current instrument call, if it has one, otherwise
   nil.

   data represents a map of 'working data' including:

   table:     a map of 'names' to instrument instance(s)
   nicknames: just like table, but only the nicknames"
  [[tag name :as name-node] nickname {:keys [table nicknames] :as data}]
  (when (= tag :name)
    (if-not nickname
 ; If the current instrument call does NOT have a nickname, assign this name
 ; node to its current entry in the table if it exists,
 ; otherwise assign it {'itself' 1}.
      (table name [{name 1}])
 ; If the current instrument call DOES have a nickname, and this name node's
 ; name is a previously defined nickname, then use those instances defined for
 ; that nickname in the nicknames table; otherwise if the node's name is not a
 ; previously defined nickname, then assign it {'itself' n}, where n is 1 greater
 ; than the highest numbered instance with that name in the table, or 1 if
 ; there are no such instances already in the table.
      (nicknames name [{name (let [instances
                                     (flatten (vals table))
                                   existing-numbers
                                     (remove nil? (map #(% name) instances))]
                               (if (seq existing-numbers)
                                 (inc (apply max existing-numbers))
                                 1))}]))))

(defn- update-data
  "Assigns instances for one instrument node.
   score is a parse-tree being rebuilt from scratch."
  [{:keys [table nicknames score] :as data}
   [_ [_ & name-nodes] music-data-node]]
  ; nickname evaluates to the nickname of the node or nil
  (let [nickname    (last (last (filter #(= (first %) :nickname) name-nodes)))
        instances   (vec (remove nil? (map #(assign % nickname data) name-nodes)))
        whole-group (vec (flatten instances))
        names       (map second name-nodes)]
    {:table (merge table
                   (zipmap names (conj instances whole-group)))
     :nicknames (if nickname
                  (assoc nicknames nickname whole-group)
                  nicknames)
     :score (conj score [:instrument
                          (apply vector :tracks whole-group)
                          music-data-node])}))

(defn- add-music-data
  "Adds the music events from an instrument node's music data node to the
   appropriate instances in a score, which is represented as a map of
   instrument instances to their respective music data."
  [score [_ [_ & instances] [_ & events]]]
  (reduce (fn [m instance] (merge-with concat m {instance events}))
  			  score
  			  instances))

(defn- consolidate-instruments
  "Returns a map of instrument instances to their consolidated music data."
  [& instrument-nodes]
  (reduce add-music-data {} instrument-nodes))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn note-length
  "Converts a number, representing a note type, e.g. 4 = quarter, 8 = eighth,
   into a number of beats. Handles dots if present."
  ([number]
    (/ 4 number))
  ([number {:keys [dots]}]
    (let [value (/ 4 number)]
      (loop [total value, factor 1/2, dots dots]
        (if (pos? dots)
          (recur (+ total (* value factor)) (* factor 1/2) (dec dots))
          total)))))

(defn duration
  "Converts a variable number of duration components* into a number of beats.

  *a note length
  *a dot (.), which multiplies the preceding number length by 1.5x
  *a tilde (~), which conditionally represents either a tie or a slur:
    - a tie adds two durations together as one note
    - a slur connects two _separate_ (different) notes together

  Slurs only appear in the final argument slot of a duration; they make the
  current note legato, effectively slurring it into the next."
  [& components]
  "to do")

(comment
  "Each instrument now has its own vector of music events, representing
   everything that instrument will do for the duration of the score.

   To do:

     - Implement a way to embed time markers of some sort among the musical
       events, so that they end up synchronized between the musical instruments.
       Each instrument by default starts its musical events at '0', i.e. the
       beginning, but the composer will be able to specify where a musical event
       will fall by using markers.

         Special cases:
           * Chords: each note in the chord starts at the same time mark. The
                     next event after the chord will be assigned that time mark
                     + the duration of the longest note/rest in the chord.
           * Voices: voices work just like chords. Each voice in the voices
                     grouping starts at the same time mark. The next event after
                     the voices grouping (whether via explicit V0: or switching
                     back from another instrument) will start whenever the last
                     voice has finished its music data... i.e. the 'largest' time
                     marking out of all the voices.

                     Implications: the composer will need to either make use of
                     named markers or make sure, when switching from instrument
                     to instrument, that he be aware that the end of the longest
                     held voice is where the next event will come in after
                     switching back to that instrument. I don't really foresee
                     this as being an inconvience. Markers will be easy to use.

     -  At this point, will probably hand off the final parse trees (one per
        instrument) to alda.sound_generator, which will hopefully be able to
        create audio segments of each instrument at each time marking, and then
        use the time markings to layer all the different audio segments together
        to create the final audio file.")


