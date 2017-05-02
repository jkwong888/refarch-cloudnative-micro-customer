apiVersion: batch/v1
kind: Job
metadata:
  name: "{{.Release.Name}}-bind-cloudantdb"
  labels:
    heritage: {{.Release.Service | quote }}
    release: {{.Release.Name | quote }}
    chart: "{{.Chart.Name}}-{{.Chart.Version}}"
  annotations:
    # This is what defines this resource as a hook. Without this line, the
    # job is considered part of the release.
    "helm.sh/hook": pre-install
    "helm.sh/hook-weight": "-5"
spec:
  template:
    metadata:
      name: "{{.Release.Name}}-bind-cloudantdb"
      labels:
        heritage: {{.Release.Service | quote }}
        release: {{.Release.Name | quote }}
        chart: "{{.Chart.Name}}-{{.Chart.Version}}"
    spec:
      restartPolicy: Never
      containers:
      - name: create-cloudantdb
        image: jkwong/bluemix-cluster-deployer
        command: [ "/bin/bash", "-c" ]
        args: ["source /scripts/bx_login.sh; bx cs init; bx cs cluster-service-bind ${KUBE_CLUSTER_NAME} default {{ .Values.cloudant.serviceName }}"]
        imagePullPolicy: Always
        env:
        - name: BX_SPACE
          valueFrom:
            configMapKeyRef:
              name: bluemix-target
              key: bluemix-space
        - name: BX_API_ENDPOINT
          valueFrom:
            configMapKeyRef:
              name: bluemix-target
              key: bluemix-api-endpoint
        - name: KUBE_CLUSTER_NAME
          valueFrom:
            configMapKeyRef:
              name: bluemix-target
              key: kube-cluster-name
        - name: BLUEMIX_API_KEY
          valueFrom:
            secretKeyRef:
              name: bluemix-api-key
              key: api-key

