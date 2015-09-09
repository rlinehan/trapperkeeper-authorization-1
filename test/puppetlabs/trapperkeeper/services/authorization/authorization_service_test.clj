(ns puppetlabs.trapperkeeper.services.authorization.authorization-service-test
  (:require
    [clojure.test :refer :all]
    [clojure.string :as str]
    [puppetlabs.trapperkeeper.app :refer [get-service]]
    [puppetlabs.trapperkeeper.authorization.testutils :refer :all]
    [puppetlabs.trapperkeeper.services :refer [defservice]]
    [puppetlabs.trapperkeeper.services.authorization.authorization-service :refer [authorization-service]]
    [puppetlabs.trapperkeeper.testutils.bootstrap :refer [with-app-with-config]]
    [puppetlabs.trapperkeeper.testutils.logging :refer [with-test-logging]]
    [ring.mock.request :as mock]
    [ring.util.response :refer [response]]
    [schema.test :as schema-test])
  (:import (java.io ByteArrayInputStream)
           (java.nio.charset Charset)))

(use-fixtures :once schema-test/validate-schemas)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Utilities

(defprotocol EchoReverseService
  (echo-reverse [this msg]))

; A simple service using authorization via ring middleware
(defservice
  echo-reverse-service EchoReverseService
  [[:AuthorizationService wrap-with-authorization-check]]
  (echo-reverse
    [this msg]
    (let [handler (fn [req]
                    (-> req
                        (:body)
                        str
                        str/reverse
                        ((partial str msg))
                        response
                        (assoc :request req)))]
      (wrap-with-authorization-check handler))))

(def minimal-config
  "Minimal config required to bootstrap AuthorizationService with
  EchoReverseService as a dependant consumer.

  An empty list representing no rules with an expected behavior of
  default-deny originating in the authorization library itself."
  {:authorization {:rules []}})

(def basic-rules
  "Basic config exercising the use case of restricting a catalog to a node"
  [{:match-request
    {:path "/puppet/v3/catalog/([^/]+)"
     :type "regex"
     :method :get}
    :allow "$1"}])

(def default-rules
  "A representative example list of rules intended to model the defaults"
  [{:match-request
    {:path "/puppet/v3/environments"
     :method :get
     :type "path"}
    :allow "*"}
   {:match-request
    {:path "^/puppet/v3/catalog/([^/]+)$"
     :method :get
     :type "regex"}
    :allow "$1"}
   {:match-request
    {:path "^/puppet/v3/node/([^/]+)$"
     :method :get
     :type "regex"}
    :allow "$1"}
   {:match-request
    {:path "^/puppet/v3/report/([^/]+)$"
     :method :put
     :type "regex"}
    :allow "$1"}
   {:match-request
    {:path "/puppet/v3/file"
     :method :any
     :type "path"}
    :allow "*"}
   {:match-request
    {:path "/puppet/v3/status"
     :method :get
     :type "path"}
    :allow "*"}
   {:match-request
    {:path "/puppet-ca/v1/certificate_revocation_list/ca"
     :method :get
     :type "path"}
    :allow "*"}
   {:match-request
    {:path "/puppet-ca/v1/certificate/ca"
     :method :get
     :type "path"}
    :allow-unauthenticated true}
   {:match-request
    {:path "/puppet-ca/v1/certificate/"
     :method :get
     :type "path"}
    :allow-unauthenticated true}
   {:match-request
    {:path   "/puppet-ca/v1/certificate_request"
     :method [:get :put]
     :type   "path"}
     :allow-unauthenticated true}])

(def catalog-request-nocert
  "A basic request for a catalog without a valid SSL cert"
  {:uri "/puppet/v3/catalog/localhost"
   :request-method :get
   :remote-addr "127.0.0.1"})

(def base-request
  "A basic request to feed into the tests"
  (request "/" :get "127.0.0.1" (create-certificate "test.domain.org") ))

(def unauthenticated-request
  "A basic unauthenticated request to feed into the tests"
  (request "/" :get "127.0.0.1"))

(defn build-ring-handler
  "Build a ring handler around the echo reverse service"
  [rules]
  (fn [request]
    (with-test-logging
      (with-app-with-config
        app
        [echo-reverse-service authorization-service]
        (assoc-in minimal-config [:authorization :rules] rules)
        (let [svc (get-service app :EchoReverseService)
              echo-handler (echo-reverse svc "Prefix: ")]
          (echo-handler request))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Tests

(deftest ^:integration wrap-handler-test
  (testing "With a default configuration list of rules"
    (let [app (build-ring-handler default-rules)]
      (let [req (assoc base-request :uri "/not/covered/by/default/rules")
            {:keys [status body]} (app req)]
        (is (= status 403))
        (is (= body "global deny all - no rules matched")))
      (let [req (-> base-request
                    (assoc :uri "/puppet/v3/environments")
                    (assoc :body "Hello World!"))
            {:keys [status body]} (app req)]
        (is (= status 200))
        (is (= body "Prefix: !dlroW olleH")))
      (let [req (assoc base-request :uri "/puppet-ca/v1/certificate/ca")
            {:keys [status body]} (app req)]
        (is (= status 200))
        (is (= body "Prefix: ")))))
  (testing "(TK-260) Authorizing unauthenticated requests"
    (let [app (build-ring-handler default-rules)]
      (let [{:keys [status body]} (app unauthenticated-request)]
        (is (= status 403))
        (is (= body "global deny all - no rules matched")))
      (let [req (assoc unauthenticated-request
                  :uri "/puppet-ca/v1/certificate/ca"
                  :body "FOOBAR")
            {:keys [status body]} (app req)]
        (is (= status 200))
        (is (= body "Prefix: RABOOF")))
      (let [req (assoc unauthenticated-request
                  :uri "/puppet-ca/v1/certificate/ca"
                  :ssl-client-cert nil)
            {:keys [status body]} (app req)]
        (is (= 200 status) ":ssl-client-cert with nil value works")
        (is (= body "Prefix: ")))
      (let [req (assoc unauthenticated-request :uri "/not/covered/by/rules")
            {:keys [status body]} (app req)]
        (is (= status 403)))
      (let [req (assoc unauthenticated-request :uri "/puppet/v3/status")
            {:keys [status body]} (app req)]
        (is (= status 403) "Unauthentic requests are denied with allow-unauthenticated false"))))
  (testing "With a minimal config of an empty list of rules"
    (let [app (build-ring-handler [])]
      (let [req (request "/path/to/foo" :get "127.0.0.1" test-domain-cert)
            {:keys [status body]} (app req)]
        (is (= status 403))
        (is (= body "global deny all - no rules matched")))))
  (testing "With a basic config protecting the catalog"
    (let [app (build-ring-handler basic-rules)]
      (let [req (assoc catalog-request-nocert :body "Hello World!")
            {:keys [status body]} (app req)]
        (is (= status 403))
        (is (= body (str "Forbidden request: (127.0.0.1) "
                         "access to /puppet/v3/catalog/localhost "
                         "(method :get) (authentic: false)"))))))
  (testing "certificate_request"
    (let [app (build-ring-handler default-rules)]
      (let [req (request "/puppet-ca/v1/certificate_request/ca"
                  :head "127.0.0.1" test-domain-cert)
            {:keys [status body]} (app req)]
        (is (= status 403))
        (is (= body "global deny all - no rules matched")))
      (let [req (request "/puppet-ca/v1/certificate_request/ca"
                  :get "127.0.0.1" test-domain-cert)
            {:keys [status]} (app req)]
        (is (= status 200)))
      (let [req (request "/puppet-ca/v1/certificate_request/ca"
                  :put "127.0.0.1" test-domain-cert)
            {:keys [status]} (app req)]
        (is (= status 200))))))

(deftest ^:integration query-params-test
  (let [app (build-ring-handler
             [{:match-request
               {:path "/puppet/v3/environments"
                :type "path"
                :query-params {"environment" ["test" "prod"]
                               "foo" ["bar"]}}
               :allow "*"}])
        req (assoc base-request
                   :uri "/puppet/v3/environments"
                   :body "Query Param Test")]
    (testing "request denied - params don't match"
      (let [{:keys [status body]}
            (app (mock/query-string req "environment=dev&foo=bar"))]
        (is (= status 403))
        (is (= body "global deny all - no rules matched"))))
    (testing "request allowed - params match"
      (let [{:keys [status body]}
            (app (mock/query-string req "environment=prod&foo=bar"))]
        (is (= status 200))
        (is (= body "Prefix: tseT maraP yreuQ"))))
    (testing "body unchanged after query param destructuring"
      (let [body-string "body=before authorization"
            body-stream-bytes (->> "UTF-8"
                                   Charset/forName
                                   (.getBytes body-string))
            body-as-input-stream (ByteArrayInputStream. body-stream-bytes)
            {:keys [request]}
              (app (-> req
                       (mock/query-string "environment=prod&foo=bar")
                       (mock/content-type "application/x-www-form-urlencoded")
                       (mock/content-length (count body-stream-bytes))
                       (assoc :body body-as-input-stream)))
            body-after-authorization (:body request)]
        (is (identical? body-as-input-stream body-after-authorization)
            "Body object changed after authorization")
        (is (= body-string (slurp body-after-authorization :encoding "UTF-8"))
            "Body stream content changed after authorization")))))
