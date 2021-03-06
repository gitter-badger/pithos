(ns io.pithos.stream
  "Read and write to cassandra from OutputStream and InputStream"
  (:import java.io.OutputStream
           java.io.InputStream
           java.nio.ByteBuffer
           org.eclipse.jetty.server.HttpInputOverHTTP
           javax.servlet.ReadListener)
  (:require [io.pithos.blob :as b]
            [io.pithos.desc :as d]
            [io.pithos.util :as u]
            [clojure.tools.logging :refer [debug error]]))

(defn chunk->ba
  [{:keys [payload]}]
  (let [array (.array payload)
        off   (.position payload)
        len   (- (.limit payload) off)]
    [array off len]))

(defn stream-to
  ([od ^OutputStream stream]
     (stream-to od stream true))
  ([od ^OutputStream stream close?]
     (let [blob   (d/blobstore od)
           blocks (b/blocks blob od)]
       (debug "got " (count blocks) "blocks")
       (try
         (doseq [{:keys [block]} blocks]
           (debug "found block " block)
           (loop [offset block]
             (when-let [chunks (b/chunks (d/blobstore od) od block offset)]
               (debug "got " (count chunks) " chunks")
               (doseq [[array off len] (map chunk->ba chunks)]
                 (.write stream array off len))
               (let [{:keys [offset chunksize]} (last chunks)]
                 (recur (+ offset chunksize))))))
         (catch Exception e
           (error e "error during read"))
         (finally
           (debug "closing after read")
           (when close?
             (.flush stream)
             (.close stream))))
       od)))

(defn stream-from
  ([^InputStream stream od]
     (stream-from stream od true))
  ([^InputStream stream od close?]
     (let [blob   (d/blobstore od)
           hash   (u/md5-init)]
       (try
         (loop [block  0
                offset 0]
           (when (>= block offset)
             (debug "marking new block")
             (b/start-block! blob od block offset))

           (let [chunk-size (b/max-chunk blob)
                 ba         (byte-array chunk-size)
                 br         (.read stream ba)]
             (if (neg? br)
               (do
                 (debug "negative write, read whole stream")
                 (d/col! od :size offset)
                 (d/col! od :checksum (u/md5-sum hash))
                 od)
               (let [chunk  (ByteBuffer/wrap ba 0 br)
                     sz     (b/chunk! blob od block offset chunk)
                     offset (+ sz offset)]
                 (u/md5-update hash ba 0 br)
                 (if (b/boundary? blob block offset)
                   (recur offset offset)
                   (recur block offset))))))
         (catch Exception e
           (error e "error during write"))
         (finally
           (debug "closing after write")
           (when close?
             (.close stream)))))))

(defn stream-copy
  [src dst]
  (let [sblob  (d/blobstore src)
        dblob  (d/blobstore dst)
        blocks (b/blocks sblob src)]
    (doseq [{:keys [block]} blocks]
      (b/start-block! dblob dst block block)
      (debug "found block " block)
      (loop [offset block]
        (when-let [chunks (b/chunks sblob src block offset)]
          (doseq [chunk chunks
                  :let [offset (:offset chunk)]]
            (b/chunk! dblob dst block offset (:payload chunk)))
          (let [{:keys [offset chunksize]} (last chunks)]
            (recur (+ offset chunksize))))))
    (d/col! dst :size (d/size src))
    (d/col! dst :checksum (d/checksum src))
    dst))

(defn stream-copy-parts
  [parts dst notifier]
  (let [dblob   (d/blobstore dst)
        hash    (u/md5-init)
        g-offset 0]
    (loop [g-offset       0
           [part & parts] parts]
      (if part
        (let [sblob  (d/blobstore part)
              blocks (b/blocks sblob part)]
          (debug "streaming part: " (d/part part))
          (recur
           (last
            (for [{:keys [block]} blocks
                  :let [g-block (+ g-offset block)]]
              (do
                (b/start-block! dblob dst g-block g-block)
                (notifier :block)
                (loop [offset block]
                  (if-let [chunks (b/chunks sblob part block offset)]
                    (do
                      (doseq [chunk chunks
                              :let [offset      (:offset chunk)
                                    payload     (:payload chunk)
                                    real-offset (+ g-offset offset)]]
                        (b/chunk! dblob dst g-block real-offset payload)
                        (notifier :chunk)
                        (let [pos (.position payload)
                              sz  (.remaining payload)
                              ba  (byte-array sz)]
                          (.get payload ba)
                          (.position payload pos)
                          (u/md5-update hash ba 0 sz)))
                      (let [{:keys [offset chunksize]} (last chunks)]
                        (recur (+ offset chunksize))))
                    (+ offset g-offset))))))
           parts))
        (do
          (d/col! dst :size g-offset)
          (d/col! dst :checksum (u/md5-sum hash))
          (debug "stored size:" g-offset "and checksum: " (u/md5-sum hash))
          dst)))))
