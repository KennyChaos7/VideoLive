package com.library.live;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.graphics.YuvImage;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;

import androidx.annotation.IntDef;

import com.library.common.UdpControlInterface;
import com.library.common.WriteFileCallback;
import com.library.live.file.WriteMp4;
import com.library.live.stream.UdpSend;
import com.library.live.vc.VoiceRecord;
import com.library.live.vd.RecordEncoderVD;
import com.library.live.vd.VDEncoder;
import com.library.live.view.PublishView;
import com.library.util.ImagUtil;
import com.library.util.OtherUtil;
import com.library.util.Rotate3dAnimation;
import com.library.util.mLog;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;

public class ScreenPublish {
    private Context context;
    private final int frameMax = 4;
    //帧率控制队列
    private ArrayBlockingQueue<Image> frameRateControlQueue = new ArrayBlockingQueue<>(frameMax);
    //视频编码
    private VDEncoder vdEncoder = null;
    //UDP发送类
    private UdpSend udpSend;
    public static final int TAKEPHOTO = 0;
    public static final int CONVERSION = 1;

    private ParameterMap map;
    //预览分辨率
    private Size previewSize;
    //推流分辨率
    private Size publishSize;
    //异步线程
    private HandlerThread controlFrameRateThread;
    private HandlerThread handlerCamearThread;
    private Handler camearHandler;
    private Handler frameHandler;

    private ScreenPublish(Context context, ParameterMap map) {
        this.context = context;
        this.map = map;
        this.publishSize = map.getPublishSize();
        this.previewSize = map.getPreviewSize();
        this.udpSend = map.getPushMode();
        initMediaProjection();
    }

    private void initMediaProjection() {

    }

    private void initCode(Size[] outputSizes) {
        if (vdEncoder == null) {
            publishSize = initSize(publishSize, outputSizes);
            previewSize = initSize(previewSize, outputSizes);

            mLog.log("pictureSize", "推流分辨率  =  " + publishSize.getWidth() + " * " + publishSize.getHeight());
            mLog.log("pictureSize", "预览分辨率  =  " + previewSize.getWidth() + " * " + previewSize.getHeight());

            //计算比例(需对调宽高)
            udpSend.setWeight((double) publishSize.getHeight() / publishSize.getWidth());
            if (map.isPreview()) {
                map.getPublishView().setWeight((double) previewSize.getHeight() / previewSize.getWidth());
            }
            vdEncoder = new VDEncoder(previewSize, publishSize, map.getFrameRate(), map.getPublishBitrate(), map.getCodetype(), udpSend);
            vdEncoder.start();
        }
    }

    private Size initSize(Size publishSize, Size[] outputSizes) {
        int numw = 10000;
        int numh = 10000;
        int num = 0;
        for (int i = 0; i < outputSizes.length; i++) {
            mLog.log("Size_app", outputSizes[i].getWidth() + "--" + outputSizes[i].getHeight());
            if (Math.abs(outputSizes[i].getWidth() - publishSize.getWidth()) <= numw) {
                numw = Math.abs(outputSizes[i].getWidth() - publishSize.getWidth());
                if (Math.abs(outputSizes[i].getHeight() - publishSize.getHeight()) <= numh) {
                    numh = Math.abs(outputSizes[i].getHeight() - publishSize.getHeight());
                    num = i;
                }
            }
        }
        return outputSizes[num];
    }

    private Bitmap mirror(Bitmap temp) {
        Matrix m = new Matrix();
        m.postScale(-1, 1);   //镜像水平翻转
//        m.postScale(1, -1);   //镜像垂直翻转
//        m.postRotate(-90);  //旋转-90度
        Bitmap newBitmap = Bitmap.createBitmap(temp, 0, 0, temp.getWidth(), temp.getHeight(), m, true);
        temp.recycle();
        return newBitmap;
    }

    public void start() {
        udpSend.startsend();
    }

    public void stop() {
        udpSend.stopsend();
    }

    public void destroy() {
        vdEncoder.destroy();
        udpSend.destroy();
        frameHandler.removeCallbacksAndMessages(null);
        controlFrameRateThread.quitSafely();
        camearHandler.removeCallbacksAndMessages(null);
        handlerCamearThread.quitSafely();
    }

    public int getPublishStatus() {
        return udpSend.getPublishStatus();
    }

    public static class Buider {
        private Context context;
        private ParameterMap map;

        @IntDef({CONVERSION, TAKEPHOTO})
        private @interface ScreenshotsMode {
        }

        public Buider(Context context, PublishView publishView) {
            map = new ParameterMap();
            this.context = context;
            map.setPublishView(publishView);
        }

        public Buider(Context context) {
            map = new ParameterMap();
            this.context = context;
        }

        //编码分辨率
        public Buider setPublishSize(int publishWidth, int publishHeight) {
            map.setPublishSize(new Size(publishWidth, publishHeight));
            return this;
        }

        public Buider setPreviewSize(int previewWidth, int previewHeight) {
            map.setPreviewSize(new Size(previewWidth, previewHeight));
            return this;
        }

        public Buider setFrameRate(int frameRate) {
            //限制最小8帧
            map.setFrameRate(Math.max(8, frameRate));
            return this;
        }

        public Buider setIsPreview(boolean isPreview) {
            map.setPreview(isPreview);
            return this;
        }

        public Buider setPublishBitrate(int publishBitrate) {
            map.setPublishBitrate(publishBitrate);
            return this;
        }

        public Buider setCollectionBitrate(int collectionBitrate) {
            map.setCollectionBitrate(collectionBitrate);
            return this;
        }

        public Buider setPublishBitrateVC(int publishbitrate_vc) {
            //限制最大48，因为发送会合并5个包，过大会导致溢出
            map.setPublishbitrate_vc(Math.min(48 * 1024, publishbitrate_vc));
            return this;
        }

        public Buider setCollectionBitrateVC(int collectionbitrate_vc) {
            map.setCollectionbitrate_vc(collectionbitrate_vc);
            return this;
        }

        public Buider setVideoCode(String codetype) {
            map.setCodetype(codetype);
            return this;
        }

        public Buider setScreenshotsMode(@ScreenshotsMode int screenshotsMode) {
            map.setScreenshotsMode(screenshotsMode);
            return this;
        }


        public Buider setVideoDirPath(String videodirpath) {
            map.setVideodirpath(videodirpath);
            return this;
        }

        public Buider setPictureDirPath(String picturedirpath) {
            map.setPicturedirpath(picturedirpath);
            return this;
        }

        public Buider setRotate(boolean rotate) {
            map.setRotate(rotate);
            return this;
        }

        public Buider setPushMode(UdpSend pushMode) {
            map.setPushMode(pushMode);
            return this;
        }

        public Buider setCenterScaleType(boolean isCenterScaleType) {
            map.getPublishView().setCenterScaleType(isCenterScaleType);
            return this;
        }

        public Buider setUdpControl(UdpControlInterface udpControl) {
            map.getPushMode().setUdpControl(udpControl);
            return this;
        }

        public Buider setMediaProjection(MediaProjection mediaProjection) {
            map.setMediaProjection(mediaProjection);
            return this;
        }

        public ScreenPublish build() {
            return new ScreenPublish(context, map);
        }
    }

    private static class ParameterMap {
        private PublishView publishView;
        private int screenshotsMode = TAKEPHOTO;
        //编码参数
        private int frameRate = 15;
        private int publishBitrate = 600 * 1024;
        private int collectionBitrate = 600 * 1024;
        private int collectionbitrate_vc = 64 * 1024;
        private int publishbitrate_vc = 24 * 1024;
        //推流分辨率,仅控制推流编码
        private Size publishSize = new Size(480, 320);
        //预览分辨率，控制预览,录制编码分辨率，图片处理分辨率
        private Size previewSize = new Size(480, 320);
        //是否翻转，默认后置
        private boolean rotate = false;
        //设置是否需要显示预览,默认显示
        private boolean isPreview = true;
        private String codetype = VDEncoder.H264;
        //录制地址
        private String videodirpath = null;
        //拍照地址
        private String picturedirpath = Environment.getExternalStorageDirectory().getPath() + File.separator + "VideoPicture";
        private UdpSend pushMode;
        private MediaProjection mediaProjection;

        private PublishView getPublishView() {
            return publishView;
        }

        private void setPublishView(PublishView publishView) {
            this.publishView = publishView;
        }

        private int getScreenshotsMode() {
            return screenshotsMode;
        }

        private void setScreenshotsMode(int screenshotsMode) {
            this.screenshotsMode = screenshotsMode;
        }

        private int getFrameRate() {
            return frameRate;
        }

        private void setFrameRate(int frameRate) {
            this.frameRate = frameRate;
        }

        private int getPublishBitrate() {
            return publishBitrate;
        }

        private void setPublishBitrate(int publishBitrate) {
            this.publishBitrate = publishBitrate;
        }

        private int getCollectionBitrate() {
            return collectionBitrate;
        }

        private void setCollectionBitrate(int collectionBitrate) {
            this.collectionBitrate = collectionBitrate;
        }

        private int getCollectionbitrate_vc() {
            return collectionbitrate_vc;
        }

        private void setCollectionbitrate_vc(int collectionbitrate_vc) {
            this.collectionbitrate_vc = collectionbitrate_vc;
        }

        private int getPublishbitrate_vc() {
            return publishbitrate_vc;
        }

        private void setPublishbitrate_vc(int publishbitrate_vc) {
            this.publishbitrate_vc = publishbitrate_vc;
        }

        private Size getPublishSize() {
            return publishSize;
        }

        private void setPublishSize(Size publishSize) {
            this.publishSize = publishSize;
        }

        private Size getPreviewSize() {
            return previewSize;
        }

        private void setPreviewSize(Size previewSize) {
            this.previewSize = previewSize;
        }

        private boolean isRotate() {
            return rotate;
        }

        private void setRotate(boolean rotate) {
            this.rotate = rotate;
        }

        private boolean isPreview() {
            return isPreview;
        }

        private void setPreview(boolean preview) {
            isPreview = preview;
        }

        private String getCodetype() {
            return codetype;
        }

        private void setCodetype(String codetype) {
            this.codetype = codetype;
        }

        private String getPicturedirpath() {
            return picturedirpath;
        }

        private void setPicturedirpath(String picturedirpath) {
            this.picturedirpath = picturedirpath;
        }

        private UdpSend getPushMode() {
            return pushMode;
        }

        private void setPushMode(UdpSend pushMode) {
            this.pushMode = pushMode;
        }

        private String getVideodirpath() {
            return videodirpath;
        }

        private void setVideodirpath(String videodirpath) {
            this.videodirpath = videodirpath;
        }

        public MediaProjection getMediaProjection() {
            return mediaProjection;
        }

        public void setMediaProjection(MediaProjection mediaProjection) {
            this.mediaProjection = mediaProjection;
        }
    }
}
