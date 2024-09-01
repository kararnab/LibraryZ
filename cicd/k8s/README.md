# Important Kubectl commands

- `kubectl get pv` - This command will display details about each PersistentVolume, including its name, capacity, access modes, status, reclaim policy, and storage class.
- `kubectl get pvc` - This command will show information about the PersistentVolumeClaims, including their names, statuses, requested storage, bound volumes, and their corresponding PersistentVolume if they are bound.
- `kubectl apply -f [filename].yaml` - Apply the k8 config written in yaml file, declarative way
- `kubectl get deployments` - To check the status of the created deployments
- `kubectl get pods` - To check the running pods
- `kubectl get svc` - To verify the service deployment. In Kubernetes, a Service is used to define a logical set of Pods that enable other Pods within the cluster to communicate with a set of Pods without needing to know the specific IP addresses of those Pods.

## Connect to PostgreSQL via kubectl
First, list the available Pods in your namespace to find the PostgreSQL Pod:
```bash
kubectl get pods
```
You will see the running pods in the following output.
```
NAME                        READY   STATUS    RESTARTS      AGE
postgres-665b7554dc-cddgq   1/1     Running   0             28s
postgres-665b7554dc-kh4tr   1/1     Running   0             28s
postgres-665b7554dc-mgprp   1/1     Running   1 (11s ago)   28s
```

Locate the name of the PostgreSQL Pod from the output.

Once you have identified the PostgreSQL Pod, use the kubectl exec command to connect the PostgreSQL pod.
```bash
kubectl exec -it postgres-665b7554dc-cddgq -- psql -h localhost -U ps_user --password -p 5432 ps_db
```
