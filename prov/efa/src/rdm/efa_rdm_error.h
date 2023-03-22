/*
 * Copyright (c) Amazon.com, Inc. or its affiliates.
 * All rights reserved.
 *
 * This software is available to you under a choice of one of two
 * licenses.  You may choose to be licensed under the terms of the GNU
 * General Public License (GPL) Version 2, available from the file
 * COPYING in the main directory of this source tree, or the
 * BSD license below:
 *
 *     Redistribution and use in source and binary forms, with or
 *     without modification, are permitted provided that the following
 *     conditions are met:
 *
 *      - Redistributions of source code must retain the above
 *        copyright notice, this list of conditions and the following
 *        disclaimer.
 *
 *      - Redistributions in binary form must reproduce the above
 *        copyright notice, this list of conditions and the following
 *        disclaimer in the documentation and/or other materials
 *        provided with the distribution.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS
 * BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN
 * ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

#ifndef EFA_RDM_ERROR_H
#define EFA_RDM_ERROR_H

#include <ctype.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "efa_errno.h"
#include "efa_rdm_peer.h"

#define HOST_ID_STR_LENGTH 19
#define NA_STR "N/A"
#define CONN_INFO_FORMAT "Local: %s Local host id: %s\tPeer: %s Peer host id: %s"
#define CONN_INFO_MAX_LENGTH (sizeof(CONN_INFO_FORMAT) + HOST_ID_STR_LENGTH * 2 + OFI_ADDRSTRLEN * 2)

/**
 * @brief Write host id string
 *
 * @param[in]	host_id	host id integer value
 * @param[out]	buf		destination string buffer
 * @return		A status code. 0 if successful, otherwise -1.
 */
static inline int efa_rdm_error_write_host_id_str(uint64_t host_id, char *buf)
{
    if (host_id && HOST_ID_STR_LENGTH == snprintf(buf, HOST_ID_STR_LENGTH + 1, "i-%017lx", host_id))
    {
        return 0;
    }

    return -1;
}

/**
 * @brief Write connection information to an error message string
 *
 * @param[out]	buf		The destination buffer
 * @return		A status code. 0 if successful, otherwise -1.
 */
static inline int efa_rdm_error_write_conn_str(const char *ep_raw_addr_str, const char *peer_raw_addr_str, uint64_t ep_host_id, uint64_t peer_host_id, char *buf)
{
    char local_host_id_str[HOST_ID_STR_LENGTH + 1], peer_host_id_str[HOST_ID_STR_LENGTH + 1];

    memset(local_host_id_str, 0, sizeof(local_host_id_str));
    memset(peer_host_id_str, 0, sizeof(peer_host_id_str));

    int ret = snprintf(buf, CONN_INFO_MAX_LENGTH, CONN_INFO_FORMAT,
                       ep_raw_addr_str ? ep_raw_addr_str : NA_STR,
                       efa_rdm_error_write_host_id_str(ep_host_id, local_host_id_str) < 0 ? NA_STR : local_host_id_str,
                       peer_raw_addr_str ? peer_raw_addr_str : NA_STR,
                       efa_rdm_error_write_host_id_str(peer_host_id, peer_host_id_str) < 0 ? NA_STR : peer_host_id_str);

    if (ret < 0 || ret >= CONN_INFO_MAX_LENGTH)
        return -1;

    return 0;
}

/**
 * @brief Allocate the error data and return its byte length
 * @param[in]    ep          RXR endpoint
 * @param[in]    addr        Remote peer fi_addr_t
 * @param[in]    err         FI_* error code(must be positive)
 * @param[in]    prov_errno  EFA provider * error code(must be positive)
 * @param[out]   ptr         Pointer to the address of error data written by this function
 * @return       If the error data was written successfully, return its
 *               byte length, otherwise 0.
 */
static inline size_t efa_rdm_error_data_alloc(struct rxr_ep *ep, fi_addr_t addr, int err, int prov_errno, void **ptr)
{
    char *err_data = NULL;
    char ep_addr_str[OFI_ADDRSTRLEN], peer_addr_str[OFI_ADDRSTRLEN];
    const char *base_msg = efa_strerror(prov_errno, NULL);
    size_t buflen = 0;
    struct efa_rdm_peer *peer = rxr_ep_get_peer(ep, addr);

    buflen = sizeof(ep_addr_str);
    rxr_ep_raw_addr_str(ep, ep_addr_str, &buflen);
    buflen = sizeof(peer_addr_str);
    rxr_ep_get_peer_raw_addr_str(ep, addr, peer_addr_str, &buflen);

    /* Reserve characters for space delimiter and null terminator */
    buflen = strlen(base_msg) + CONN_INFO_MAX_LENGTH + 2;
    err_data = malloc(buflen);
    if (!err_data) {
        return 0;
    }

    strcpy(err_data, base_msg);
    strcat(err_data, " ");

    if (efa_rdm_error_write_conn_str(ep_addr_str, peer_addr_str, ep->host_id, peer ? peer->host_id : 0, err_data + strlen(err_data)) < 0) {
        return 0;
    }

    *ptr = err_data;
    return buflen;
}

#endif /* EFA_RDM_ERROR_H */
