(ns io.pithos.util
  "A few utility functions, used in several places"
  (:import [java.io                PipedInputStream PipedOutputStream]
           [java.util              TimeZone Calendar]
           [java.lang              Math]
           [org.jboss.netty.buffer ChannelBuffers])
  (:require [clojure.string :refer [lower-case]]))

(defn md5-init
  "Yield an MD5 MessageDigest instance"
  []
  (doto (java.security.MessageDigest/getInstance "MD5") (.reset)))

(defn md5-update
  "Add data from byte-array in a MessageDigest instance"
  [hash ba from to]
  (locking hash
    (doto hash
      (.update ba from to))))

(defn md5-sum
  "Yield a padded hex string of an MD5 digest"
  [hash]
  (let [digest (.toString (java.math.BigInteger. 1 (.digest hash)) 16)
        pad    (apply str (repeat (- 32 (count digest)) "0"))]
    (str pad digest)))

(defn inc-prefix
  "Given an object path, yield the next semantic one."
  [p]
  (when (seq p)
    (let [[c & s] (reverse p)
          reversed (conj s (-> c int inc char))]
      (apply str (reverse reversed)))))

(def byte-factors
  "1024 factor of corresponding storage unit"
  {"k" 1 "m" 2 "g" 3 "t" 4 "p" 5})

(def byte-pattern
  "Regular expression pattern for data size"
  #"([0-9]+)(([kKmMgGtTpP])[bB]?)?")

(defn to-bytes
  "Parse an input string into a byte amount, the input
   string can be suffixed by a unit specifier"
  [input & [param]]
  (when input
    (if-let [[_ amount _ factor] (re-find byte-pattern (str input))]
      (do
        (long
         (* (Long/parseLong amount)
            (if factor
              (Math/pow 1024 (get byte-factors (lower-case factor)))
              1))))
      (throw (ex-info (format "invalid byte amount [%s]: %s"
                              (or param "") input) {})))))


(defn piped-input-stream
  "yield two interconnected PipedInputStream and PipedOutputStream"
  []
  (let [os  (PipedOutputStream.)
        is  (PipedInputStream. os)]
    [is os]))

(defn parse-uuid
  "Parse the string representation of a uuid"
  [s]
  (java.util.UUID/fromString s))

(defn ->channel-buffer
  "Convert a heap-buffer to a channel-buffer"
  [hb]
  (ChannelBuffers/copiedBuffer hb))

(def utc
  "The UTC timezone, only fetched once"
  (delay (TimeZone/getTimeZone "UTC")))

(defn iso8601-timestamp
  "String representation of the current timestamp in UTC"
  []
  (javax.xml.bind.DatatypeConverter/printDateTime (Calendar/getInstance @utc)))
