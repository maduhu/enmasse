#!/bin/bash
OC_DIR=$1
ARTIFACTS_DIR=$2

export PATH="$OC_DIR:$PATH"

function runcmd {
    echo ''
    echo "$1 : "
    $1
    echo ''
    echo '#######################################################################'
}

for pod in `oc get pods -o jsonpath='{.items[*].metadata.name}'`
do
    for container in `oc get pod $pod -o jsonpath='{.spec.containers[*].name}'`
    do
        runcmd "oc logs -c $container $pod > ${ARTIFACTS_DIR}/${pod}_${container}.log"
        if [ "$container" == "router" ]; then
            runcmd "oc rsh -c $container $pod qdmanage query --type=address > ${ARTIFACTS_DIR}/${pod}_${container}_router_address.txt"
            runcmd "oc rsh -c $container $pod qdmanage query --type=connection > ${ARTIFACTS_DIR}/${pod}_${container}_router_connection.txt"
            runcmd "oc rsh -c $container $pod qdmanage query --type=connector > ${ARTIFACTS_DIR}/${pod}_${container}_router_connector.txt"
        fi
    done
done

for log in `find /tmp/testlogs`
do
    runcmd "cp $log $ARTIFACTS_DIR/"
done
