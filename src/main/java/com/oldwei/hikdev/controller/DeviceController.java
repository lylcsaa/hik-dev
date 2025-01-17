package com.oldwei.hikdev.controller;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson.JSONObject;
import com.oldwei.hikdev.component.AliyunComponent;
import com.oldwei.hikdev.constant.DataCachePrefixConstant;
import com.oldwei.hikdev.entity.Device;
import com.oldwei.hikdev.entity.StreamAddress;
import com.oldwei.hikdev.service.IHikAlarmDataService;
import com.oldwei.hikdev.service.IHikCameraService;
import com.oldwei.hikdev.service.IHikDeviceService;
import com.oldwei.hikdev.util.DataCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;

/**
 * @author oldwei
 * @date 2021-7-7 14:37
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("api/device")
public class DeviceController {

    private final IHikDeviceService hikDeviceService;

    private final DataCache dataCache;

    private final IHikCameraService hikCameraService;

    private final IHikAlarmDataService hikAlarmDataService;

    private final AliyunComponent aliyunComponent;

    /**
     * 设备注册登录
     *
     * @param device 设备基本信息IP、username、password、port
     * @return 登录结果 true/false
     */
    @PostMapping("deviceLogin")
    public boolean deviceLogin(@RequestBody Device device) {
        return this.hikDeviceService.deviceLogin(device);
    }

    /**
     * 设备注销退出
     *
     * @param device 设备IP
     * @return 注销结果 true/false
     */
    @PostMapping("deviceClean")
    public boolean deviceClean(@RequestBody Device device) {
        return this.hikDeviceService.clean(device.getIp());
    }

    /**
     * 设备sdk打开预览，获取到流数据，进行推流，需要填写推送地址例如：rtmp://ip:port/live/stream
     * 自行部署流媒体服务器
     *
     * @param ip 设备IP
     * @return 拉流地址
     */
    @PostMapping("startPushStream/{ip}")
    public JSONObject startPushStream(@PathVariable String ip) {
        JSONObject result = new JSONObject();
        Integer userId = this.dataCache.getInteger(DataCachePrefixConstant.HIK_REG_USERID_IP + ip);
        if (null == userId || userId < 0) {
            result.put("code", -1);
            result.put("msg", "设备注册异常，可能是没注册，也可能是设备有问题，设备状态userId：" + userId);
            return result;
        }
        Integer getPreviewSucValue = this.dataCache.getInteger(DataCachePrefixConstant.HIK_PREVIEW_VIEW_IP + ip);
        if (null != getPreviewSucValue && getPreviewSucValue != -1) {
            result.put("code", -1);
            result.put("msg", "设备已经在预览状态了，请勿重复开启，设备状态userId：" + userId);
            return result;
        }
        String stream = RandomUtil.randomString(32);
        String pushStreamDomain = this.aliyunComponent.getPushStreamDomain(stream);
        StreamAddress pullStreamDomain = this.aliyunComponent.getPullStreamDomain(stream);
        this.hikCameraService.startPushStream(userId, ip, pushStreamDomain);
        this.dataCache.set(DataCachePrefixConstant.HIK_PUSH_PULL_STREAM_ADDRESS_IP + ip, pullStreamDomain);
        result.put("code", 0);
        result.put("data", pullStreamDomain);
        return result;
    }

    @GetMapping("streamList/{ip}")
    public JSONObject getStreamList(@PathVariable String ip) {
        JSONObject result = new JSONObject();
        StreamAddress streamAddress = (StreamAddress) this.dataCache.get(DataCachePrefixConstant.HIK_PUSH_PULL_STREAM_ADDRESS_IP + ip);
        result.put("code", 0);
        result.put("data", streamAddress);
        return result;
    }

    /**
     * 使用rtsp推流 海康rtsp取流地址参考：https://www.jianshu.com/p/8efcea89b11f
     *
     * @param jsonObject rtspUrl拉流地址：rtsp://ip:port/live/stream
     *                   pushUrl推流地址：rtmp://ip:port/live/stream
     * @return
     * @throws IOException
     */
    @PostMapping("pushRtspToRtmp")
    public String pushRtspToRtmp(@RequestBody JSONObject jsonObject) {
        //TODO 只需要一个IP地址，推送至指定流媒体服务器，在配置文件中配置推送的流媒体服务器
        String ip = jsonObject.getString("ip");
        String rtspUrl = jsonObject.getString("rtspUrl");
        String pushUrl = jsonObject.getString("pushUrl");
        if (StrUtil.isBlank(ip) || StrUtil.isBlank(rtspUrl) || StrUtil.isBlank(pushUrl)) {
            return "缺少参数: ip rtspUrl 或 pushUrl";
        }
        this.hikCameraService.pushRtspToRtmp(ip, rtspUrl, pushUrl);
        return "推流成功";
    }

    /**
     * 退出推流
     *
     * @param device 退出推流的设备IP
     */
    @PostMapping("existPushStream")
    public boolean existPushStream(@RequestBody Device device) {
        return this.hikCameraService.existPushStream(device.getIp());
    }

    /**
     * 设备布防
     *
     * @param device 设备IP
     */
    @PostMapping("setupAlarm")
    public JSONObject setupAlarm(@RequestBody Device device) {
        return this.hikAlarmDataService.setupAlarmChan(device.getIp());
    }

    /**
     * 设备撤防
     *
     * @param device 设备IP
     */
    @PostMapping("closeAlarm")
    public JSONObject closeAlarm(@RequestBody Device device) {
        return this.hikAlarmDataService.closeAlarmChan(device.getIp());
    }
}
