oc apply -f ./argo-apps/kafka-operator.yaml
oc apply -f ./argo-apps/knative-operator.yaml

oc apply -f ./argo-apps/kafka-cluster.yaml
oc apply -f ./argo-apps/knative-serving.yaml
oc apply -f ./argo-apps/keycloak.yaml
oc apply -f ./argo-apps/kafka-ui.yaml

oc apply -f ./argo-apps/order.yaml
oc apply -f ./argo-apps/allocation.yaml
oc apply -f ./argo-apps/payment.yaml
oc apply -f ./argo-apps/frontend.yaml