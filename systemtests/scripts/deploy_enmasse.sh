#!/usr/bin/env bash
source ./systemtests/scripts/test_func.sh
SKIP_SETUP=${1:-false}
ENMASSE_DIR=${2}
KUBEADM=${3}
REG_API_SERVER=${4:-true}
export REGISTER_API_SERVER=${REG_API_SERVER}

download_enmasse

if [[ ${SKIP_SETUP} != 'true' ]]; then
    setup_test ${ENMASSE_DIR} ${KUBEADM}
fi
