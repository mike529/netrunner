(ns game.main
  (:import [org.zeromq ZMQ ZMQQueue])
  (:require [cheshire.core :refer [parse-string generate-string]]
            [cheshire.generate :refer [add-encoder encode-str]]
            [game.macros :refer [effect]]
            [game.core :refer [game-states system-msg pay gain draw end-run] :as core])
  (:gen-class :main true))

(add-encoder java.lang.Object encode-str)

(def commands
  {"say" core/say
   "system-msg" #(system-msg %1 %2 (:msg %3))
   "change" core/change
   "move" core/move-card
   "mulligan" core/mulligan
   "keep" core/keep-hand
   "start-turn" core/start-turn
   "end-turn" core/end-turn
   "draw" core/click-draw
   "credit" core/click-credit
   "purge" core/do-purge
   "remove-tag" core/remove-tag
   "play" core/play
   "rez" #(core/rez %1 %2 (:card %3) nil)
   "derez" #(core/derez %1 %2 (:card %3))
   "run" core/click-run
   "no-action" core/no-action
   "continue" core/continue
   "access" core/successful-run
   "jack-out" core/jack-out
   "advance" core/advance
   "score" core/score
   "choice" core/resolve-prompt
   "select" core/select
   "shuffle" core/shuffle-deck
   "ability" core/play-ability})

(defn convert [args]
  (let [params (parse-string (String. args) true)]
    (if (or (get-in params [:args :card]))
      (update-in params [:args :card :zone] #(map (fn [k] (if (string? k) (keyword k) k)) %))
      params)))

(defn -main []
  (let [ctx (ZMQ/context 1)
        worker-url "inproc://responders"
        router (doto (.socket ctx ZMQ/ROUTER) (.bind "tcp://127.0.0.1:1043"))
        dealer (doto (.socket ctx ZMQ/DEALER) (.bind worker-url))]
    (dotimes [n 2]
      (.start
       (Thread.
        (fn []
          (let [socket (.socket ctx ZMQ/REP)]
            (.connect socket worker-url)
            (while true
              (let [{:keys [gameid action command side args text] :as msg} (convert (.recv socket))
                    state (@game-states gameid)]
                (try
                  (case action
                    "start" (core/init-game msg)
                    "remove" (swap! game-states dissoc gameid)
                    "do" ((commands command) state (keyword side) args)
                    "notification" (swap! state update-in [:log]
                                          #(conj % {:user "__system__" :text text}))
                    "quit" (system-msg state (keyword side) "left the game"))
                  (if (#{"start" "do"} action)
                    (.send socket (generate-string (assoc @(@game-states gameid) :action action)) ZMQ/NOBLOCK)
                    (.send socket (generate-string "ok") ZMQ/NOBLOCK))
                  (catch Exception e
                    (println "Error in Thread " n action command (get-in args [:card :title]) e)
                    (.send socket (generate-string "error") ZMQ/NOBLOCK))))))))))

    (.start (Thread. #(.run (ZMQQueue. ctx router dealer))))
    (println "Listening on port 1043 for incoming commands...")))
