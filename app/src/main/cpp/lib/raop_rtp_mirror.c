//
// Created by Administrator on 2019/1/29/029.
//

#include "raop_rtp_mirror.h"

#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <assert.h>
#include <errno.h>

#include "raop.h"
#include "netutils.h"
#include "compat.h"
#include "logger.h"
#include "byteutils.h"
#include "mirror_buffer.h"
#include "stream.h"

#define MAX_MIRROR_PAYLOAD_SIZE (16 * 1024 * 1024)

struct h264codec_s {
    unsigned char compatibility;
    short lengthofPPS;
    short lengthofSPS;
    unsigned char level;
    unsigned char numberOfPPS;
    unsigned char* picture_parameter_set;
    unsigned char profile_high;
    unsigned char reserved3andSPS;
    unsigned char reserved6andNAL;
    unsigned char* sequence;
    unsigned char version;
};

struct raop_rtp_mirror_s {
    logger_t *logger;
    raop_callbacks_t callbacks;

    /* Buffer to handle all resends */
    mirror_buffer_t *buffer;

    raop_rtp_mirror_t *mirror;
    /* Remote address as sockaddr */
    struct sockaddr_storage remote_saddr;
    socklen_t remote_saddr_len;

    /* MUTEX LOCKED VARIABLES START */
    /* These variables only edited mutex locked */
    int running;
    int joined;

    int flush;
    thread_handle_t thread_mirror;
    thread_handle_t thread_time;
    mutex_handle_t run_mutex;

    mutex_handle_t time_mutex;
    cond_handle_t time_cond;
    /* MUTEX LOCKED VARIABLES END */
    int mirror_data_sock, mirror_time_sock, stream_fd;

    unsigned short mirror_data_lport;
    unsigned short mirror_timing_rport;
    unsigned short mirror_timing_lport;

    unsigned char *payload_in_buffer;
    unsigned char *payload_buffer;
    unsigned int payload_capacity;
};

static int
raop_rtp_mirror_ensure_payload_capacity(raop_rtp_mirror_t *raop_rtp_mirror, unsigned int payloadsize)
{
    if (payloadsize > MAX_MIRROR_PAYLOAD_SIZE) {
        logger_log(raop_rtp_mirror->logger, LOGGER_WARNING, "Mirror payload too large: %u", payloadsize);
        return -1;
    }
    if (payloadsize <= raop_rtp_mirror->payload_capacity) {
        return 0;
    }

    unsigned char *payload_in = realloc(raop_rtp_mirror->payload_in_buffer, payloadsize);
    if (!payload_in) {
        return -1;
    }
    raop_rtp_mirror->payload_in_buffer = payload_in;

    unsigned char *payload = realloc(raop_rtp_mirror->payload_buffer, payloadsize);
    if (!payload) {
        return -1;
    }
    raop_rtp_mirror->payload_buffer = payload;
    raop_rtp_mirror->payload_capacity = payloadsize;
    return 0;
}

static int
recv_full(int fd, unsigned char *buffer, unsigned int length)
{
    unsigned int readstart = 0;
    while (readstart < length) {
        int ret = recv(fd, buffer + readstart, length - readstart, 0);
        if (ret <= 0) {
            return -1;
        }
        readstart += ret;
    }
    return 0;
}

static int
raop_rtp_mirror_discard_payload(raop_rtp_mirror_t *raop_rtp_mirror, int stream_fd, unsigned int payloadsize)
{
    if (payloadsize == 0) {
        return 0;
    }
    if (raop_rtp_mirror_ensure_payload_capacity(raop_rtp_mirror, payloadsize) < 0) {
        return -1;
    }
    return recv_full(stream_fd, raop_rtp_mirror->payload_in_buffer, payloadsize);
}

static int
raop_rtp_parse_remote(raop_rtp_mirror_t *raop_rtp_mirror, const unsigned char *remote, int remotelen)
{
    char current[25];
    int family;
    int ret;
    assert(raop_rtp_mirror);
    if (remotelen == 4) {
        family = AF_INET;
    } else if (remotelen == 16) {
        family = AF_INET6;
    } else {
        return -1;
    }
    memset(current, 0, sizeof(current));
    sprintf(current, "%d.%d.%d.%d", remote[0], remote[1], remote[2], remote[3]);
    logger_log(raop_rtp_mirror->logger, LOGGER_DEBUG, "raop_rtp_parse_remote ip = %s", current);
    ret = netutils_parse_address(family, current,
                                 &raop_rtp_mirror->remote_saddr,
                                 sizeof(raop_rtp_mirror->remote_saddr));
    if (ret < 0) {
        return -1;
    }
    raop_rtp_mirror->remote_saddr_len = ret;
    return 0;
}

#define NO_FLUSH (-42)
raop_rtp_mirror_t *raop_rtp_mirror_init(logger_t *logger, raop_callbacks_t *callbacks, const unsigned char *remote, int remotelen,
                                        const unsigned char *aeskey, const unsigned char *ecdh_secret, unsigned short timing_rport)
{
    raop_rtp_mirror_t *raop_rtp_mirror;

    assert(logger);
    assert(callbacks);

    raop_rtp_mirror = calloc(1, sizeof(raop_rtp_mirror_t));
    if (!raop_rtp_mirror) {
        return NULL;
    }
    raop_rtp_mirror->logger = logger;
    raop_rtp_mirror->mirror_timing_rport = timing_rport;

    memcpy(&raop_rtp_mirror->callbacks, callbacks, sizeof(raop_callbacks_t));
    raop_rtp_mirror->buffer = mirror_buffer_init(logger, aeskey, ecdh_secret);
    if (!raop_rtp_mirror->buffer) {
        free(raop_rtp_mirror);
        return NULL;
    }
    if (raop_rtp_parse_remote(raop_rtp_mirror, remote, remotelen) < 0) {
        mirror_buffer_destroy(raop_rtp_mirror->buffer);
        free(raop_rtp_mirror);
        return NULL;
    }
    raop_rtp_mirror->running = 0;
    raop_rtp_mirror->joined = 1;
    raop_rtp_mirror->flush = NO_FLUSH;
    raop_rtp_mirror->mirror_data_sock = -1;
    raop_rtp_mirror->mirror_time_sock = -1;
    raop_rtp_mirror->stream_fd = -1;

    MUTEX_CREATE(raop_rtp_mirror->run_mutex);
    MUTEX_CREATE(raop_rtp_mirror->time_mutex);
    COND_CREATE(raop_rtp_mirror->time_cond);
    return raop_rtp_mirror;
}

void
raop_rtp_init_mirror_aes(raop_rtp_mirror_t *raop_rtp_mirror, uint64_t streamConnectionID)
{
    mirror_buffer_init_aes(raop_rtp_mirror->buffer, streamConnectionID);
}

/**
 * ntp
 */
static THREAD_RETVAL
raop_rtp_mirror_thread_time(void *arg)
{
    raop_rtp_mirror_t *raop_rtp_mirror = arg;
    assert(raop_rtp_mirror);
    struct sockaddr_storage saddr;
    socklen_t saddrlen;
    unsigned char packet[128];
    int packetlen;
    int first = 0;
    unsigned char time[48]={35,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0};
    uint64_t base = now_us();
    uint64_t rec_pts = 0;
    while (1) {
        MUTEX_LOCK(raop_rtp_mirror->run_mutex);
        if (!raop_rtp_mirror->running) {
            MUTEX_UNLOCK(raop_rtp_mirror->run_mutex);
            break;
        }
        MUTEX_UNLOCK(raop_rtp_mirror->run_mutex);
        uint64_t send_time = now_us() - base + rec_pts;

        byteutils_put_timeStamp(time, 40, send_time);
        logger_log(raop_rtp_mirror->logger, LOGGER_DEBUG, "raop_rtp_mirror_thread_time send time 48 bytes, port = %d", raop_rtp_mirror->mirror_timing_rport);
        struct sockaddr_in *addr = (struct sockaddr_in *)&raop_rtp_mirror->remote_saddr;
        addr->sin_port = htons(raop_rtp_mirror->mirror_timing_rport);
        int sendlen = sendto(raop_rtp_mirror->mirror_time_sock, (char *)time, sizeof(time), 0, (struct sockaddr *) &raop_rtp_mirror->remote_saddr, raop_rtp_mirror->remote_saddr_len);
        logger_log(raop_rtp_mirror->logger, LOGGER_DEBUG, "raop_rtp_mirror_thread_time sendlen = %d", sendlen);

        saddrlen = sizeof(saddr);
        packetlen = recvfrom(raop_rtp_mirror->mirror_time_sock, (char *)packet, sizeof(packet), 0,
                             (struct sockaddr *)&saddr, &saddrlen);
        logger_log(raop_rtp_mirror->logger, LOGGER_DEBUG, "raop_rtp_mirror_thread_time receive time packetlen = %d", packetlen);
        if (packetlen < 48) {
            continue;
        }
        // 16-24 The time when the system clock was last set or updated.
        uint64_t Reference_Timestamp = byteutils_read_timeStamp(packet, 16);
        // 24-32 Local time of the sender when the NTP request packet leaves the sender. T1
        uint64_t Origin_Timestamp = byteutils_read_timeStamp(packet, 24);
        // 32-40 Local time of the receiving end when the NTP request packet arrives at the receiving end. T2
        uint64_t Receive_Timestamp = byteutils_read_timeStamp(packet, 32);
        // 40-48 Transmit Timestamp: The local time of the responder when the response message leaves the responder. T3
        uint64_t Transmit_Timestamp = byteutils_read_timeStamp(packet, 40);

        // FIXME: Let's write this simply.
        rec_pts = Receive_Timestamp;

        if (first == 0) {
            first++;
        } else {
            struct timeval now;
            struct timespec outtime;
            MUTEX_LOCK(raop_rtp_mirror->time_mutex);
            gettimeofday(&now, NULL);
            outtime.tv_sec = now.tv_sec + 3;
            outtime.tv_nsec = now.tv_usec * 1000;
            int ret = pthread_cond_timedwait(&raop_rtp_mirror->time_cond, &raop_rtp_mirror->time_mutex, &outtime);
            MUTEX_UNLOCK(raop_rtp_mirror->time_mutex);
            //sleepms(3000);
        }
    }
    logger_log(raop_rtp_mirror->logger, LOGGER_INFO, "Exiting UDP raop_rtp_mirror_thread_time thread");
    return 0;
}
//#define DUMP_H264

#define RAOP_PACKET_LEN 32768
/**
 * Mirror
 */
static THREAD_RETVAL
raop_rtp_mirror_thread(void *arg)
{
    raop_rtp_mirror_t *raop_rtp_mirror = arg;
    int stream_fd = -1;
    unsigned char packet[128];
    memset(packet, 0 , 128);
    unsigned int readstart = 0;
    uint64_t pts_base = 0;
    uint64_t pts = 0;
    assert(raop_rtp_mirror);

#ifdef DUMP_H264
    // C decrypted
    FILE* file = fopen("/sdcard/111.h264", "wb");
    // Encrypted source file
    FILE* file_source = fopen("/sdcard/111.source", "wb");

    FILE* file_len = fopen("/sdcard/111.len", "wb");
#endif
    while (1) {
        fd_set rfds;
        struct timeval tv;
        int nfds, ret;
        MUTEX_LOCK(raop_rtp_mirror->run_mutex);
        if (!raop_rtp_mirror->running) {
            MUTEX_UNLOCK(raop_rtp_mirror->run_mutex);
            break;
        }
        MUTEX_UNLOCK(raop_rtp_mirror->run_mutex);
        /* Set timeout value to 5ms */
        tv.tv_sec = 0;
        tv.tv_usec = 5000;

        /* Get the correct nfds value and set rfds */
        FD_ZERO(&rfds);
        if (stream_fd == -1) {
            FD_SET(raop_rtp_mirror->mirror_data_sock, &rfds);
            nfds = raop_rtp_mirror->mirror_data_sock+1;
        } else {
            FD_SET(stream_fd, &rfds);
            nfds = stream_fd+1;
        }
        ret = select(nfds, &rfds, NULL, NULL, &tv);
        if (ret == 0) {
            /* Timeout happened */
            continue;
        } else if (ret == -1) {
            /* FIXME: Error happened */
            logger_log(raop_rtp_mirror->logger, LOGGER_INFO, "Error in select");
            break;
        }
        if (stream_fd == -1 && FD_ISSET(raop_rtp_mirror->mirror_data_sock, &rfds)) {
            struct sockaddr_storage saddr;
            socklen_t saddrlen;

            logger_log(raop_rtp_mirror->logger, LOGGER_INFO, "Accepting client");
            saddrlen = sizeof(saddr);
            stream_fd = accept(raop_rtp_mirror->mirror_data_sock, (struct sockaddr *)&saddr, &saddrlen);
            if (stream_fd == -1) {
                /* FIXME: Error happened */
                logger_log(raop_rtp_mirror->logger, LOGGER_INFO, "Error in accept %d %s", errno, strerror(errno));
                break;
            }
            MUTEX_LOCK(raop_rtp_mirror->run_mutex);
            raop_rtp_mirror->stream_fd = stream_fd;
            MUTEX_UNLOCK(raop_rtp_mirror->run_mutex);
        }
        if (stream_fd != -1 && FD_ISSET(stream_fd, &rfds)) {
            // Packetlen initial 0
            ret = recv(stream_fd, packet + readstart, 4 - readstart, 0);
            if (ret == 0) {
                /* TCP socket closed */
                logger_log(raop_rtp_mirror->logger, LOGGER_INFO, "TCP socket closed");
                break;
            } else if (ret == -1) {
                /* FIXME: Error happened */
                logger_log(raop_rtp_mirror->logger, LOGGER_INFO, "Error in recv");
                break;
            }
            readstart += ret;
            if (readstart < 4) {
                continue;
            }
            if ((packet[0] == 80 && packet[1] == 79 && packet[2] == 83 && packet[3] == 84) || (packet[0] == 71 && packet[1] == 69 && packet[2] == 84)) {
                // POST or GET
                logger_log(raop_rtp_mirror->logger, LOGGER_DEBUG, "handle http data");
            } else {
                // Common data block
                if (recv_full(stream_fd, packet + readstart, 128 - readstart) < 0) {
                    logger_log(raop_rtp_mirror->logger, LOGGER_INFO, "TCP socket closed while reading frame header");
                    break;
                }
                int payloadsize = byteutils_get_int(packet, 0);
                if (payloadsize < 0) {
                    logger_log(raop_rtp_mirror->logger, LOGGER_WARNING, "Invalid negative mirror payload size %d", payloadsize);
                    break;
                }
                // FIXME: The calculation method here needs to be confirmed again.
                short payloadtype = (short) (byteutils_get_short(packet, 4) & 0xff);
                short payloadoption = byteutils_get_short(packet, 6);

                // Processing content data
                if (payloadtype == 0) {
                    uint64_t payloadntp = byteutils_get_long(packet, 8);
                    // Reading time
                    if (pts_base == 0) {
                        pts_base = ntptopts(payloadntp);
                    } else {
                        pts =  ntptopts(payloadntp) - pts_base;
                    }
                    if (raop_rtp_mirror_ensure_payload_capacity(raop_rtp_mirror, payloadsize) < 0) {
                        logger_log(raop_rtp_mirror->logger, LOGGER_ERR, "Unable to allocate mirror payload buffer");
                        break;
                    }
                    unsigned char* payload_in = raop_rtp_mirror->payload_in_buffer;
                    unsigned char* payload = raop_rtp_mirror->payload_buffer;
                    if (recv_full(stream_fd, payload_in, payloadsize) < 0) {
                        logger_log(raop_rtp_mirror->logger, LOGGER_INFO, "TCP socket closed while reading video payload");
                        break;
                    }
                    readstart = payloadsize;
                    //logger_log(raop_rtp_mirror->logger, LOGGER_DEBUG, "readstart = %d", readstart);
#ifdef DUMP_H264
                    fwrite(payload_in, payloadsize, 1, file_source);
                    fwrite(&readstart, sizeof(readstart), 1, file_len);
#endif
                    // Decrypt data
                    mirror_buffer_decrypt(raop_rtp_mirror->buffer, payload_in, payload, payloadsize);
                    int nalu_size = 0;
                    int nalu_num = 0;
                    while (nalu_size + 4 <= payloadsize) {
                        int nc_len = (payload[nalu_size + 0] << 24) | (payload[nalu_size + 1] << 16) | (payload[nalu_size + 2] << 8) | (payload[nalu_size + 3]);
                        if (nc_len <= 0 || nalu_size + 4 + nc_len > payloadsize) {
                            break;
                        }
                        payload[nalu_size + 0] = 0;
                        payload[nalu_size + 1] = 0;
                        payload[nalu_size + 2] = 0;
                        payload[nalu_size + 3] = 1;
                        //int nalutype = payload[4] & 0x1f;
                        //logger_log(raop_rtp_mirror->logger, LOGGER_DEBUG, "nalutype = %d", nalutype);
                        nalu_size += nc_len + 4;
                        nalu_num++;
                    }
                    //logger_log(raop_rtp_mirror->logger, LOGGER_DEBUG, "nalu_size = %d, payloadsize = %d nalu_num = %d", nalu_size, payloadsize, nalu_num);

                    // Write file
#ifdef DUMP_H264
                    fwrite(payload, payloadsize, 1, file);
#endif
                    h264_decode_struct h264_data;
                    h264_data.data_len = payloadsize;
                    h264_data.data = payload;
                    h264_data.frame_type = 1;
                    h264_data.nTimeStamp = 0;
                    h264_data.pts = pts;
                    raop_rtp_mirror->callbacks.video_process(raop_rtp_mirror->callbacks.cls, &h264_data);
                } else if ((payloadtype & 255) == 1) {
                    float mWidthSource = byteutils_get_float(packet, 40);
                    float mHeightSource = byteutils_get_float(packet, 44);
                    float mWidth = byteutils_get_float(packet, 56);
                    float mHeight =byteutils_get_float(packet, 60);
                    logger_log(raop_rtp_mirror->logger, LOGGER_DEBUG, "mWidthSource = %f mHeightSource = %f mWidth = %f mHeight = %f", mWidthSource, mHeightSource, mWidth, mHeight);
                    /*int mRotateMode = 0;

                    int p = payloadtype >> 8;
                    if (p == 4) {
                        mRotateMode = 1;
                    } else if (p == 7) {
                        mRotateMode = 3;
                    } else if (p != 0) {
                        mRotateMode = 2;
                    }*/

                    // sps_pps This piece of data is not encrypted
                    if (payloadsize < 8) {
                        memset(packet, 0, 128);
                        readstart = 0;
                        continue;
                    }
                    if (raop_rtp_mirror_ensure_payload_capacity(raop_rtp_mirror, payloadsize) < 0) {
                        logger_log(raop_rtp_mirror->logger, LOGGER_ERR, "Unable to allocate SPS/PPS payload buffer");
                        break;
                    }
                    unsigned char *payload = raop_rtp_mirror->payload_buffer;
                    if (recv_full(stream_fd, payload, payloadsize) < 0) {
                        logger_log(raop_rtp_mirror->logger, LOGGER_INFO, "TCP socket closed while reading SPS/PPS payload");
                        break;
                    }
                    h264codec_t h264;
                    h264.version = payload[0];
                    h264.profile_high = payload[1];
                    h264.compatibility = payload[2];
                    h264.level = payload[3];
                    h264.reserved6andNAL = payload[4];
                    h264.reserved3andSPS = payload[5];
                    h264.lengthofSPS = (short) (((payload[6] & 255) << 8) + (payload[7] & 255));
                    if (h264.lengthofSPS <= 0 || h264.lengthofSPS + 11 > payloadsize) {
                        memset(packet, 0, 128);
                        readstart = 0;
                        continue;
                    }
                    logger_log(raop_rtp_mirror->logger, LOGGER_DEBUG, "lengthofSPS = %d", h264.lengthofSPS);
                    h264.sequence = malloc(h264.lengthofSPS);
                    if (!h264.sequence) {
                        break;
                    }
                    memcpy(h264.sequence, payload + 8, h264.lengthofSPS);
                    h264.numberOfPPS = payload[h264.lengthofSPS + 8];
                    h264.lengthofPPS = (short) (((payload[h264.lengthofSPS + 9] & 2040) + payload[h264.lengthofSPS + 10]) & 255);
                    if (h264.lengthofPPS <= 0 || h264.lengthofSPS + 11 + h264.lengthofPPS > payloadsize) {
                        free(h264.sequence);
                        memset(packet, 0, 128);
                        readstart = 0;
                        continue;
                    }
                    h264.picture_parameter_set = malloc(h264.lengthofPPS);
                    if (!h264.picture_parameter_set) {
                        free(h264.sequence);
                        break;
                    }
                    logger_log(raop_rtp_mirror->logger, LOGGER_DEBUG, "lengthofPPS = %d", h264.lengthofPPS);
                    memcpy(h264.picture_parameter_set, payload + h264.lengthofSPS + 11, h264.lengthofPPS);
                    if (h264.lengthofSPS + h264.lengthofPPS < 102400) {
                        // Copy spspps
                        int sps_pps_len = (h264.lengthofSPS + h264.lengthofPPS) + 8;
                        unsigned char sps_pps[sps_pps_len];
                        sps_pps[0] = 0;
                        sps_pps[1] = 0;
                        sps_pps[2] = 0;
                        sps_pps[3] = 1;
                        memcpy(sps_pps + 4, h264.sequence, h264.lengthofSPS);
                        sps_pps[h264.lengthofSPS + 4] = 0;
                        sps_pps[h264.lengthofSPS + 5] = 0;
                        sps_pps[h264.lengthofSPS + 6] = 0;
                        sps_pps[h264.lengthofSPS + 7] = 1;
                        memcpy(sps_pps + h264.lengthofSPS + 8, h264.picture_parameter_set, h264.lengthofPPS);
#ifdef DUMP_H264
                        fwrite(sps_pps, sps_pps_len, 1, file);
#endif
                        h264_decode_struct h264_data;
                        h264_data.data_len = sps_pps_len;
                        h264_data.data = sps_pps;
                        h264_data.frame_type = 0;
                        h264_data.nTimeStamp = 0;
                        h264_data.pts = 0;
                        raop_rtp_mirror->callbacks.video_process(raop_rtp_mirror->callbacks.cls, &h264_data);
                    }
                    free(h264.picture_parameter_set);
                    free(h264.sequence);
                } else if (payloadtype == (short) 2) {
                    if (raop_rtp_mirror_discard_payload(raop_rtp_mirror, stream_fd, payloadsize) < 0) {
                        break;
                    }
                } else if (payloadtype == (short) 4) {
                    if (raop_rtp_mirror_discard_payload(raop_rtp_mirror, stream_fd, payloadsize) < 0) {
                        break;
                    }
                } else {
                    if (raop_rtp_mirror_discard_payload(raop_rtp_mirror, stream_fd, payloadsize) < 0) {
                        break;
                    }
                }
            }
            memset(packet, 0 , 128);
            readstart = 0;
        }
    }

    /* Close the stream file descriptor */
    if (stream_fd != -1) {
        closesocket(stream_fd);
    }

    MUTEX_LOCK(raop_rtp_mirror->run_mutex);
    raop_rtp_mirror->running = 0;
    raop_rtp_mirror->stream_fd = -1;
    MUTEX_UNLOCK(raop_rtp_mirror->run_mutex);

    if (raop_rtp_mirror->mirror_data_sock != -1) {
        closesocket(raop_rtp_mirror->mirror_data_sock);
        raop_rtp_mirror->mirror_data_sock = -1;
    }
    if (raop_rtp_mirror->mirror_time_sock != -1) {
        closesocket(raop_rtp_mirror->mirror_time_sock);
        raop_rtp_mirror->mirror_time_sock = -1;
    }

    MUTEX_LOCK(raop_rtp_mirror->time_mutex);
    COND_SIGNAL(raop_rtp_mirror->time_cond);
    MUTEX_UNLOCK(raop_rtp_mirror->time_mutex);

    if (raop_rtp_mirror->callbacks.stream_stopped) {
        raop_rtp_mirror->callbacks.stream_stopped(raop_rtp_mirror->callbacks.cls);
    }
    logger_log(raop_rtp_mirror->logger, LOGGER_INFO, "Exiting TCP raop_rtp_mirror_thread thread");
#ifdef DUMP_H264
    fclose(file);
    fclose(file_source);
    fclose(file_len);
#endif
    return 0;
}

void
raop_rtp_start_mirror(raop_rtp_mirror_t *raop_rtp_mirror, int use_udp, unsigned short mirror_timing_rport, unsigned short * mirror_timing_lport,
                      unsigned short *mirror_data_lport)
{
    int use_ipv6 = 0;

    assert(raop_rtp_mirror);

    MUTEX_LOCK(raop_rtp_mirror->run_mutex);
    if (raop_rtp_mirror->running || !raop_rtp_mirror->joined) {
        MUTEX_UNLOCK(raop_rtp_mirror->run_mutex);
        return;
    }

    //raop_rtp_mirror->mirror_timing_rport = mirror_timing_rport;
    if (raop_rtp_mirror->remote_saddr.ss_family == AF_INET6) {
        use_ipv6 = 1;
    }
    use_ipv6 = 0;
    if (raop_rtp_init_mirror_sockets(raop_rtp_mirror, use_ipv6) < 0) {
        logger_log(raop_rtp_mirror->logger, LOGGER_INFO, "Initializing sockets failed");
        MUTEX_UNLOCK(raop_rtp_mirror->run_mutex);
        return;
    }
    if (mirror_timing_lport) *mirror_timing_lport = raop_rtp_mirror->mirror_timing_lport;
    if (mirror_data_lport) *mirror_data_lport = raop_rtp_mirror->mirror_data_lport;

    /* Create the thread and initialize running values */
    raop_rtp_mirror->running = 1;
    raop_rtp_mirror->joined = 0;

    THREAD_CREATE(raop_rtp_mirror->thread_mirror, raop_rtp_mirror_thread, raop_rtp_mirror);
    THREAD_CREATE(raop_rtp_mirror->thread_time, raop_rtp_mirror_thread_time, raop_rtp_mirror);
    MUTEX_UNLOCK(raop_rtp_mirror->run_mutex);
}

void raop_rtp_mirror_stop(raop_rtp_mirror_t *raop_rtp_mirror) {
    assert(raop_rtp_mirror);

    int stream_fd = -1;
    int data_sock = -1;
    int time_sock = -1;

    MUTEX_LOCK(raop_rtp_mirror->run_mutex);
    if (raop_rtp_mirror->joined) {
        MUTEX_UNLOCK(raop_rtp_mirror->run_mutex);
        return;
    }
    raop_rtp_mirror->running = 0;
    stream_fd = raop_rtp_mirror->stream_fd;
    data_sock = raop_rtp_mirror->mirror_data_sock;
    time_sock = raop_rtp_mirror->mirror_time_sock;
    MUTEX_UNLOCK(raop_rtp_mirror->run_mutex);

    if (stream_fd != -1) shutdown(stream_fd, SHUT_RDWR);
    if (data_sock != -1) shutdown(data_sock, SHUT_RDWR);
    if (time_sock != -1) shutdown(time_sock, SHUT_RDWR);

    MUTEX_LOCK(raop_rtp_mirror->time_mutex);
    COND_SIGNAL(raop_rtp_mirror->time_cond);
    MUTEX_UNLOCK(raop_rtp_mirror->time_mutex);

    THREAD_JOIN(raop_rtp_mirror->thread_mirror);

    MUTEX_LOCK(raop_rtp_mirror->time_mutex);
    COND_SIGNAL(raop_rtp_mirror->time_cond);
    MUTEX_UNLOCK(raop_rtp_mirror->time_mutex);

    THREAD_JOIN(raop_rtp_mirror->thread_time);
    if (raop_rtp_mirror->mirror_data_sock != -1) {
        closesocket(raop_rtp_mirror->mirror_data_sock);
        raop_rtp_mirror->mirror_data_sock = -1;
    }
    if (raop_rtp_mirror->mirror_time_sock != -1) {
        closesocket(raop_rtp_mirror->mirror_time_sock);
        raop_rtp_mirror->mirror_time_sock = -1;
    }

    /* Mark thread as joined */
    MUTEX_LOCK(raop_rtp_mirror->run_mutex);
    raop_rtp_mirror->joined = 1;
    MUTEX_UNLOCK(raop_rtp_mirror->run_mutex);
}

void raop_rtp_mirror_destroy(raop_rtp_mirror_t *raop_rtp_mirror) {
    if (raop_rtp_mirror) {
        raop_rtp_mirror_stop(raop_rtp_mirror);
        MUTEX_DESTROY(raop_rtp_mirror->run_mutex);
        MUTEX_DESTROY(raop_rtp_mirror->time_mutex);
        COND_DESTROY(raop_rtp_mirror->time_cond);
        mirror_buffer_destroy(raop_rtp_mirror->buffer);
        free(raop_rtp_mirror->payload_in_buffer);
        free(raop_rtp_mirror->payload_buffer);
        free(raop_rtp_mirror);
    }
}

static int
raop_rtp_init_mirror_sockets(raop_rtp_mirror_t *raop_rtp_mirror, int use_ipv6)
{
    int dsock = -1, tsock = -1;
    unsigned short tport = 0, dport = 0;

    assert(raop_rtp_mirror);

    dsock = netutils_init_socket(&dport, use_ipv6, 0);
    tsock = netutils_init_socket(&tport, use_ipv6, 1);
    if (dsock == -1 || tsock == -1) {
        goto sockets_cleanup;
    }
    struct timeval timeout;
    timeout.tv_sec = 1;
    timeout.tv_usec = 0;
    setsockopt(tsock, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));

    /* Listen to the data socket if using TCP */
    if (listen(dsock, 1) < 0)
        goto sockets_cleanup;


    /* Set socket descriptors */
    raop_rtp_mirror->mirror_data_sock = dsock;
    raop_rtp_mirror->mirror_time_sock = tsock;

    /* Set port values */
    raop_rtp_mirror->mirror_data_lport = dport;
    raop_rtp_mirror->mirror_timing_lport = tport;
    return 0;

    sockets_cleanup:
    if (tsock != -1) closesocket(tsock);
    if (dsock != -1) closesocket(dsock);
    return -1;
}
