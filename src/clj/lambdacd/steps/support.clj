(ns lambdacd.steps.support
  (:require [clojure.string :as s]
            [clojure.core.async :as async]
            [lambdacd.internal.execution :as execution]
            [clojure.walk :as walk]
            [lambdacd.step-id :as step-id]
            [lambdacd.steps.status :as status]
            [lambdacd.steps.result :as step-results])
  (:import (java.io PrintWriter Writer StringWriter PrintStream)
           (org.apache.commons.io.output WriterOutputStream)))

(defn- do-chain-steps-final-result [merged-result all-outputs]
  (assoc merged-result :outputs all-outputs))

(defn merge-step-results-with-joined-output [a b]
  (step-results/merge-step-results a b :resolvers [step-results/status-resolver
                                      step-results/merge-nested-maps-resolver
                                      step-results/join-output-resolver
                                      step-results/second-wins-resolver]))


(defn- do-chain-steps [stop-on-step-failure args ctx steps]
  "run the given steps one by one until a step fails and merge the results.
   results of one step are the inputs for the next one."
  (loop [counter     1
         x           (first steps)
         rest        (rest steps)
         result      {}
         all-outputs {}
         args        args]
    (if (nil? x)
      (do-chain-steps-final-result result all-outputs)
      (let [step-result     (x args ctx)
            complete-result (merge-step-results-with-joined-output result step-result)
            next-args       (merge args complete-result)
            step-failed     (and
                              (not= :success (:status step-result))
                              (not= nil step-result))
            child-step-id (step-id/child-id (:step-id ctx) counter)
            new-all-outputs (assoc all-outputs child-step-id step-result)]
        (if (and stop-on-step-failure step-failed)
          (do-chain-steps-final-result complete-result new-all-outputs)
          (recur (inc counter)
                 (first rest)
                 (next rest)
                 complete-result
                 new-all-outputs
                 next-args))))))

(defn always-chain-steps
  ([args ctx & steps]
   (do-chain-steps false args ctx steps)))

(defn chain-steps
  ([args ctx & steps]
   (do-chain-steps true args ctx steps)))


(defn to-fn [form]
  (let [f# (first form)
        r# (next form)]
    (if (map? form)
      `(fn [& _# ] ~form)
      `(fn [args# ctx#] (~f# args# ctx# ~@r#)))))

;; Placeholders where args and ctx are injected by the chaining-macro.
(def injected-args)
(def injected-ctx)

(defn replace-args-and-ctx [args ctx]
  (fn [x]
    (cond
      (and
        (symbol? x)
        (= (var injected-args) (resolve x))) args
      (and
        (symbol? x)
        (= (var injected-ctx) (resolve x))) ctx
      :else x)))

(defn to-fn-with-args [form]
  (let [f# (first form)
        ctx (gensym "ctx")
        args (gensym "args")
        r# (walk/postwalk
             (replace-args-and-ctx args ctx)
             (next form))]
    (if (map? form)
      `(fn [& _# ] ~form)
      `(fn [~args ~ctx] (~f# ~@r#)))))

(defn- do-chaining [chain-fn args ctx forms]
  (let [fns (vec (map to-fn-with-args forms))]
    `(apply ~chain-fn ~args ~ctx ~fns)))

(defmacro chaining [args ctx & forms]
  "syntactic sugar for chain-steps. can work with arbitrary code and can inject args and ctx"
  (do-chaining chain-steps args ctx forms))

(defmacro always-chaining [args ctx & forms]
  "syntactic sugar for always-chain-steps. can work with arbitrary code and can inject args and ctx"
  (do-chaining always-chain-steps args ctx forms))

(defn last-step-status-wins [step-result]
  (let [winning-status (->> step-result
                           :outputs
                           (sort-by #(vec (first %)))
                           last
                           second
                           :status)]
    (assoc step-result :status winning-status)))

(defn- append-output [msg]
  (fn [old-output]
    (str old-output msg "\n")))

(defn new-printer []
  (atom ""))

(defn set-output [ctx msg]
  (async/>!! (:result-channel ctx) [:out msg]))

(defn print-to-output [ctx printer msg]
  (let [new-out (swap! printer (append-output msg))]
    (set-output ctx new-out)))

(defn printed-output [printer]
  @printer)


(defn killed? [ctx]
  @(:is-killed ctx))

(defmacro if-not-killed [ctx & body]
  `(if (killed? ~ctx)
     (do
       (async/>!! (:result-channel ~ctx) [:status :killed])
       {:status :killed})
     ~@body))

(defn merge-globals [step-results]
  (or (:global (reduce execution/keep-globals {} step-results)) {}))

(defn merge-step-results [step-results]
  (reduce execution/merge-two-step-results {} step-results))

; not part of the public interface, just public for the macro
(defn writer-to-ctx [ctx]
  (let [buf (StringWriter.)]
    {:writer (proxy [Writer] []
              (write [& [x ^Integer off ^Integer len]]
                (cond
                  (number? x) (.append buf (char x))
                  (not off) (.append buf x)
                  ; the CharSequence overload of append takes an *end* idx, not length!
                  (instance? CharSequence x) (.append buf ^CharSequence x (int off) (int (+ len off)))
                  :else (do
                          (.append buf (String. ^chars x) off len))))
              (flush []
                (set-output ctx (.toString (.getBuffer buf)))))
     :buffer (.getBuffer buf)}))

(defmacro capture-output [ctx & body]
  `(let [{x#      :writer
          buffer# :buffer} (writer-to-ctx ~ctx)
         body-result# (binding [*out* x#]
                        (do ~@body))]
     (if (associative? body-result#)
       (update body-result# :out #(if (nil? %) (str buffer#) (str buffer# "\n" % ))))))
