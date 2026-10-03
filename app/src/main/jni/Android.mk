LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE    := protected

LOCAL_SRC_FILES := protected.c neigh.c

LOCAL_LDFLAGS += -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384

include $(BUILD_SHARED_LIBRARY)
