package com.oldwei.hikdev.service.impl;

import cn.hutool.core.codec.Base64;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.oldwei.hikdev.constant.HikConstant;
import com.oldwei.hikdev.constant.DataCachePrefixConstant;
import com.oldwei.hikdev.entity.access.AccessPeople;
import com.oldwei.hikdev.entity.QueryRequest;
import com.oldwei.hikdev.entity.access.UserInfoSearch;
import com.oldwei.hikdev.service.IHikDevService;
import com.oldwei.hikdev.service.IHikUserService;
import com.oldwei.hikdev.structure.BYTE_ARRAY;
import com.oldwei.hikdev.structure.NET_DVR_JSON_DATA_CFG;
import com.oldwei.hikdev.structure.NET_DVR_XML_CONFIG_INPUT;
import com.oldwei.hikdev.structure.NET_DVR_XML_CONFIG_OUTPUT;
import com.oldwei.hikdev.util.DataCache;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * @author zhangjie
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HikUserServiceImpl implements IHikUserService {
    private final IHikDevService hikDevService;
    private final DataCache dataCache;

    @Override
    public JSONObject getAbility(JSONObject jsonObject) {
        JSONObject result = new JSONObject();
        result.put("event", jsonObject.getString("event"));
        // 获取用户句柄
        Integer longUserId = this.dataCache.getInteger(DataCachePrefixConstant.HIK_REG_USERID_IP + jsonObject.getString("ip"));
        if (null == longUserId) {
            result.put("code", -1);
            result.put("msg", "设备状态未注册！");
            return result;
        }
        String strURL = "GET /ISAPI/AccessControl/UserInfo/capabilities?format=json";
        BYTE_ARRAY ptrUrl = new BYTE_ARRAY(HikConstant.BYTE_ARRAY_LEN);
        System.arraycopy(strURL.getBytes(), 0, ptrUrl.byValue, 0, strURL.length());
        ptrUrl.write();

        //获取能力集时输入参数为空即可
//        BYTE_ARRAY ptrInBuffer = new BYTE_ARRAY(HikConstant.ISAPI_DATA_LEN);
//        ptrInBuffer.read();
//        String strInbuffer = "";
//        ptrInBuffer.byValue = strInbuffer.getBytes();
//        ptrInBuffer.write();

        NET_DVR_XML_CONFIG_INPUT struXMLInput = new NET_DVR_XML_CONFIG_INPUT();
        struXMLInput.read();
        struXMLInput.dwSize = struXMLInput.size();
        struXMLInput.lpRequestUrl = ptrUrl.getPointer();
        struXMLInput.dwRequestUrlLen = ptrUrl.byValue.length;
        // ptrInBuffer.getPointer();
        struXMLInput.lpInBuffer = null;
        // ptrInBuffer.byValue.length;
        struXMLInput.dwInBufferSize = 0;
        struXMLInput.write();

        BYTE_ARRAY ptrStatusByte = new BYTE_ARRAY(HikConstant.ISAPI_STATUS_LEN);
        ptrStatusByte.read();

        BYTE_ARRAY ptrOutByte = new BYTE_ARRAY(HikConstant.ISAPI_DATA_LEN);
        ptrOutByte.read();

        NET_DVR_XML_CONFIG_OUTPUT struXMLOutput = new NET_DVR_XML_CONFIG_OUTPUT();
        struXMLOutput.read();
        struXMLOutput.dwSize = struXMLOutput.size();
        struXMLOutput.lpOutBuffer = ptrOutByte.getPointer();
        struXMLOutput.dwOutBufferSize = ptrOutByte.size();
        struXMLOutput.lpStatusBuffer = ptrStatusByte.getPointer();
        struXMLOutput.dwStatusSize = ptrStatusByte.size();
        struXMLOutput.write();

        if (!hikDevService.NET_DVR_STDXMLConfig(longUserId, struXMLInput, struXMLOutput)) {
            int iErr = hikDevService.NET_DVR_GetLastError();
            log.error("NET_DVR_STDXMLConfig失败，错误号：" + iErr);
            result.put("code", -1);
            result.put("msg", "NET_DVR_STDXMLConfig失败，错误号：" + iErr);
            return result;
        } else {
            struXMLOutput.read();
            ptrOutByte.read();
            ptrStatusByte.read();
            String strOutXML = new String(ptrOutByte.byValue).trim();
            String strStatus = new String(ptrStatusByte.byValue).trim();
            JSONObject strJson = new JSONObject();
            strJson.put("strOutXMl", JSONObject.parseObject(strOutXML));
            strJson.put("strStatus", JSONObject.parseObject(strStatus));
            result.put("code", 1);
            result.put("msg", "获取设备能力集成功");
            result.put("data", strJson);
        }
        return result;
    }

    @Override
    public JSONObject searchUserInfo(String ip, String[] employeeNos, QueryRequest queryRequest) {
        JSONObject result = new JSONObject();
        //  数组
        String strInBuffer = "POST /ISAPI/AccessControl/UserInfo/Search?format=json";
        Integer lHandler = this.startRemoteConfig(ip, strInBuffer);
        if (lHandler < 0) {
            result.put("code", -1);
            result.put("msg", "SearchUserInfo NET_DVR_StartRemoteConfig 失败,错误码为" + this.hikDevService.NET_DVR_GetLastError());
            return result;
        } else {
            //组装查询的JSON报文，这边查询的是所有的卡
            JSONObject jsonObject = new JSONObject();
            JSONObject jsonSearchCond = new JSONObject();

            //如果需要查询指定的工号人员信息，把下面注释的内容去除掉即可
            if (employeeNos.length > 0) {
                JSONArray employeeNoList = new JSONArray();
                for (String no : employeeNos) {
                    JSONObject employeeNo = new JSONObject();
                    //employeeNo可选，string，人员 ID
                    employeeNo.put("employeeNo", no);
                    employeeNoList.add(employeeNo);
                }
                //EmployeeNoList可选，人员 ID 列表
                jsonSearchCond.put("EmployeeNoList", employeeNoList);
            }
            //必填，string，搜索记录唯一标识，用来确认上层客户端是否为同一个（倘若是同一个,设备记录内存,下次搜索加快速度）
            //暂时写死吧，反正同一个查询还快
            jsonSearchCond.put("searchID", "123e4567-e89b-12d3-a456-426655440000");
            //必填，integer，查询结果在结果列表中的起始位置。
            //当记录条数很多时，一次查询不能获取所有的记录，下一次查询时指定位置可以查询后面的
            //记录（若设备支持的最大 totalMatches 为 M 个，但是当前设备已存储的 totalMatches 为 N 个
            //（N<=M），则该字段的合法范围为 0~N-1）
            //分页当前第几页
            jsonSearchCond.put("searchResultPosition", queryRequest.getPageNum());
            //必填，integer，本次协议调用可获取的最大记录数（如maxResults 值大于设备能力集返回的范围，则设备按照能力集最大值返回，设备不进行报错）
            //分页大小
            jsonSearchCond.put("maxResults", queryRequest.getPageSize());
            jsonObject.put("UserInfoSearchCond", jsonSearchCond);

            String strInbuff = jsonObject.toJSONString();
            log.info("查询人员的json报文:{}", strInbuff);

            //把string传递到Byte数组中，后续用.getPointer()方法传入指针地址中。
            BYTE_ARRAY ptrInbuff = new BYTE_ARRAY(strInbuff.length());
            System.arraycopy(strInbuff.getBytes(), 0, ptrInbuff.byValue, 0, strInbuff.length());
            ptrInbuff.write();
            //定义接收结果的结构体
            BYTE_ARRAY ptrOutuff = new BYTE_ARRAY(10 * 1024);
            IntByReference pInt = new IntByReference(0);
            while (true) {
                int dwState = this.hikDevService.NET_DVR_SendWithRecvRemoteConfig(lHandler, ptrInbuff.getPointer(), strInbuff.length(), ptrOutuff.getPointer(), 10 * 1024, pInt);
                if (dwState == -1 || dwState == HikConstant.NET_SDK_CONFIG_STATUS_FAILED || dwState == HikConstant.NET_SDK_CONFIG_STATUS_EXCEPTION) {
                    result.put("code", -1);
                    result.put("msg", "查询人员失败/异常，错误码：" + this.hikDevService.NET_DVR_GetLastError());
                    break;
                } else if (dwState == HikConstant.NET_SDK_CONFIG_STATUS_NEEDWAIT) {
                    log.info("配置等待");
                } else if (dwState == HikConstant.NET_SDK_CONFIG_STATUS_SUCCESS) {
                    ptrOutuff.read();
                    String userInfo = new String(ptrOutuff.byValue).trim();
                    JSONObject user = JSONObject.parseObject(userInfo);
                    result.put("code", 1);
                    result.put("msg", "查询人员成功");
                    //因为name是字节数组，所以需要自己转一下
                    UserInfoSearch userInfoSearch = user.getJSONObject("UserInfoSearch").toJavaObject(UserInfoSearch.class);
                    userInfoSearch.getUserInfo().forEach(people -> {
                        String realName = new String(people.getName()).trim();
                        log.info("打印要转码的名字：{}", realName);
                        people.setRealName(realName);
                    });
                    result.put("data", userInfoSearch);
                    break;
                } else if (dwState == HikConstant.NET_SDK_CONFIG_STATUS_FINISH) {
                    log.info("获取人员完成");
                    break;
                }
            }

            if (!this.hikDevService.NET_DVR_StopRemoteConfig(lHandler)) {
                result.put("code", -1);
                result.put("msg", "NET_DVR_StopRemoteConfig接口调用失败，错误码：" + this.hikDevService.NET_DVR_GetLastError());
            }
        }
        return result;
    }

    @Override
    public JSONObject addUserInfo(String ip, AccessPeople people) {
        JSONObject result = new JSONObject();
        String strInBuffer = "POST /ISAPI/AccessControl/UserInfo/Record?format=json";
        Integer lHandler = this.startRemoteConfig(ip, strInBuffer);
        if (lHandler < 0) {
            result.put("code", -1);
            result.put("msg", "AddUserInfo NET_DVR_StartRemoteConfig 接口调用失败，错误码：" + this.hikDevService.NET_DVR_GetLastError());
            return result;
        } else {
            String employeeNo = people.getEmployeeNo();
            //name需要转为字节数组
            //将中文字符编码之后用数组拷贝的方式，避免因为编码导致的长度问题
            byte[] name = people.getRealName().getBytes(StandardCharsets.UTF_8);

            JSONObject valid = new JSONObject();
            valid.put("beginTime", "2017-08-01T17:30:08");
            valid.put("enable", true);
            valid.put("endTime", "2030-08-01T17:30:08");

            JSONObject rightPlan = new JSONObject();
            rightPlan.put("doorNo", 1);
            rightPlan.put("planTemplateNo", "1,3,5");

            JSONArray rightPlanArray = new JSONArray();
            rightPlanArray.add(rightPlan);

            JSONObject userInfo = new JSONObject();
            userInfo.put("Valid", valid);
            userInfo.put("checkUser", false);
            userInfo.put("doorRight", "1");
            userInfo.put("RightPlan", rightPlanArray);
            userInfo.put("employeeNo", employeeNo);
            userInfo.put("floorNumber", 1);
            userInfo.put("maxOpenDoorTime", 0);
            userInfo.put("name", name);
            userInfo.put("openDelayEnabled", false);
            userInfo.put("password", "123456");
            userInfo.put("roomNumber", 1);
            userInfo.put("userType", "normal");

            JSONObject userInfoBuff = new JSONObject();
            userInfoBuff.put("UserInfo", userInfo);
            String userInfoBuffString = userInfoBuff.toJSONString();
            int iStringSize = userInfoBuffString.length();
            BYTE_ARRAY ptrByte = new BYTE_ARRAY(iStringSize);
            System.arraycopy(userInfoBuffString.getBytes(), 0, ptrByte.byValue, 0, userInfoBuffString.length());

            ptrByte.write();

            System.out.println(new String(ptrByte.byValue));

            BYTE_ARRAY ptrOutuff = new BYTE_ARRAY(1024);

            IntByReference pInt = new IntByReference(0);
            while (true) {
                int dwState = this.hikDevService.NET_DVR_SendWithRecvRemoteConfig(lHandler, ptrByte.getPointer(), iStringSize, ptrOutuff.getPointer(), 1024, pInt);
                //读取返回的json并解析
                ptrOutuff.read();
                String strResult = new String(ptrOutuff.byValue).trim();
                System.out.println("dwState:" + dwState + ",strResult:" + strResult);

                JSONObject jsonResult = JSONObject.parseObject(strResult);
                int statusCode = jsonResult.getIntValue("statusCode");
                String statusString = jsonResult.getString("statusString");


                if (dwState == -1||dwState == HikConstant.NET_SDK_CONFIG_STATUS_FAILED||dwState == HikConstant.NET_SDK_CONFIG_STATUS_EXCEPTION) {
                    result.put("code", -1);
                    result.put("msg", "NET_DVR_SendWithRecvRemoteConfig接口调用失败，错误码：" + this.hikDevService.NET_DVR_GetLastError());
                    result.put("data", "下发人员失败, json retun:" + jsonResult.toJSONString());
                    break;
                } else if (dwState == HikConstant.NET_SDK_CONFIG_STATUS_NEEDWAIT) {
                    System.out.println("配置等待");
                } else if (dwState == HikConstant.NET_SDK_CONFIG_STATUS_SUCCESS) {
                    //返回NET_SDK_CONFIG_STATUS_SUCCESS代表流程走通了，但并不代表下发成功，比如有些设备可能因为人员已存在等原因下发失败，所以需要解析Json报文
                    result.put("code", 0);
                    result.put("msg", "下发人员成功: json retun:" + jsonResult.toJSONString());
                    if (statusCode != 1) {
                        log.info("下发人员成功,但是有异常情况:" + jsonResult.toJSONString()+"错误码："+this.hikDevService.NET_DVR_GetLastError());
                    } else {
                        log.info("下发人员成功: json retun:" + jsonResult.toJSONString());
                    }
                    break;
                } else if (dwState == HikConstant.NET_SDK_CONFIG_STATUS_FINISH) {
                    //下发人员时：dwState其实不会走到这里，因为设备不知道我们会下发多少个人，所以长连接需要我们主动关闭
                    log.info("下发人员完成");
                    break;
                }
            }
            if (!this.hikDevService.NET_DVR_StopRemoteConfig(lHandler)) {
                result.put("code", -1);
                result.put("msg", "NET_DVR_StopRemoteConfig接口调用失败，错误码：" + this.hikDevService.NET_DVR_GetLastError());
                System.out.println("NET_DVR_StopRemoteConfig接口调用失败，错误码：" + this.hikDevService.NET_DVR_GetLastError());
            } else {
                log.info("NET_DVR_StopRemoteConfig接口成功");
            }
            log.info("AddUserInfo NET_DVR_StartRemoteConfig 成功!");
        }
        return result;
    }

    @Override
    public JSONObject modifyUserInfo(String ip, AccessPeople people) {
        JSONObject result = new JSONObject();
        String strInBuffer = "PUT /ISAPI/AccessControl/UserInfo/Modify?format=json";
        Integer lHandler = this.startRemoteConfig(ip, strInBuffer);
        if (lHandler < 0) {
            result.put("code", -1);
            result.put("msg", "modifyUserInfo NET_DVR_StartRemoteConfig 失败,错误码为" + this.hikDevService.NET_DVR_GetLastError());
            return result;
        } else {
            log.info("modifyUserInfo NET_DVR_StartRemoteConfig 成功!");
            String employeeNo = people.getEmployeeNo();
            //name需要转为字节数组
            byte[] name = people.getRealName().getBytes(StandardCharsets.UTF_8);
            JSONObject valid = new JSONObject();
            valid.put("beginTime", "2017-08-01T17:30:08");
            valid.put("enable", true);
            valid.put("endTime", "2030-08-01T17:30:08");

            JSONObject rightPlan = new JSONObject();
            rightPlan.put("doorNo", 1);
            rightPlan.put("planTemplateNo", "1,3,5");

            JSONArray rightPlanArray = new JSONArray();
            rightPlanArray.add(rightPlan);

            JSONObject userInfo = new JSONObject();
            userInfo.put("Valid", valid);
            userInfo.put("checkUser", false);
            userInfo.put("doorRight", "1");
            userInfo.put("RightPlan", rightPlanArray);
            userInfo.put("employeeNo", employeeNo);
            userInfo.put("floorNumber", 1);
            userInfo.put("maxOpenDoorTime", 0);
            userInfo.put("name", name);
            userInfo.put("openDelayEnabled", false);
            userInfo.put("password", "123456");
            userInfo.put("roomNumber", 1);
            userInfo.put("userType", "normal");

            JSONObject userInfoBuff = new JSONObject();
            userInfoBuff.put("UserInfo", userInfo);
            String userInfoBuffString = userInfoBuff.toJSONString();
            int iStringSize = userInfoBuffString.length();
            BYTE_ARRAY ptrByte = new BYTE_ARRAY(iStringSize);
            //将中文字符编码之后用数组拷贝的方式，避免因为编码导致的长度问题
            System.arraycopy(userInfoBuffString.getBytes(StandardCharsets.UTF_8), 0, ptrByte.byValue, 0, userInfoBuffString.length());
            ptrByte.write();

            log.info("修改人员JSON数据：" + new String(ptrByte.byValue));

            BYTE_ARRAY ptrOutuff = new BYTE_ARRAY(1024);

            IntByReference pInt = new IntByReference(0);
            while (true) {
                int dwState = this.hikDevService.NET_DVR_SendWithRecvRemoteConfig(lHandler, ptrByte.getPointer(), iStringSize, ptrOutuff.getPointer(), 1024, pInt);
                //读取返回的json并解析
                ptrOutuff.read();
                String strResult = new String(ptrOutuff.byValue).trim();
                System.out.println("dwState:" + dwState + ",strResult:" + strResult);

                JSONObject jsonResult = JSONObject.parseObject(strResult);
                int statusCode = jsonResult.getIntValue("statusCode");
                String statusString = jsonResult.getString("statusString");

                if (dwState == -1 || dwState == HikConstant.NET_SDK_CONFIG_STATUS_FAILED || dwState == HikConstant.NET_SDK_CONFIG_STATUS_EXCEPTION) {
                    result.put("code", -1);
                    result.put("msg", "修改人员失败, json return:" + jsonResult.toJSONString() + "NET_DVR_SendWithRecvRemoteConfig接口调用失败，错误码：" + this.hikDevService.NET_DVR_GetLastError());
                    break;
                } else if (dwState == HikConstant.NET_SDK_CONFIG_STATUS_NEEDWAIT) {
                    System.out.println("配置等待");
                } else if (dwState == HikConstant.NET_SDK_CONFIG_STATUS_SUCCESS) {
                    //返回NET_SDK_CONFIG_STATUS_SUCCESS代表流程走通了，但并不代表下发成功，比如有些设备可能因为人员已存在等原因下发失败，所以需要解析Json报文
                    result.put("code", 0);
                    result.put("msg", "修改人员成功:" + jsonResult.toJSONString());
                    if (statusCode != 1) {
                        log.info("修改人员成功,但是有异常情况:" + jsonResult.toJSONString());
                    } else {
                        log.info("修改人员成功: json return:" + jsonResult.toJSONString());
                    }
                    break;
                } else if (dwState == HikConstant.NET_SDK_CONFIG_STATUS_FINISH) {
                    //下发人员时：dwState其实不会走到这里，因为设备不知道我们会下发多少个人，所以长连接需要我们主动关闭
                    log.info("修改人员完成");
                    break;
                }
            }
            if (!this.hikDevService.NET_DVR_StopRemoteConfig(lHandler)) {
                result.put("code", -1);
                result.put("msg", "NET_DVR_StopRemoteConfig接口调用失败，错误码：" + this.hikDevService.NET_DVR_GetLastError());
            } else {
                log.info("NET_DVR_StopRemoteConfig接口成功");
            }
        }
        return result;
    }

    @Override
    public JSONObject addMultiUserInfo(String ip, List<AccessPeople> peopleList) {
        JSONObject result = new JSONObject();
        JSONArray jsonArray = new JSONArray();
        String strInBuffer = "POST /ISAPI/AccessControl/UserInfo/Record?format=json";
        Integer lHandler = this.startRemoteConfig(ip, strInBuffer);
        if (lHandler < 0) {
            result.put("code", -1);
            result.put("msg", "addMultiUserInfo NET_DVR_StartRemoteConfig 失败,错误码为" + this.hikDevService.NET_DVR_GetLastError());
            return result;
        } else {
            log.info("addMultiUserInfo NET_DVR_StartRemoteConfig 成功!");
            // 结果集合
            for (AccessPeople people : peopleList) {
                // 单个的结果
                JSONObject jsonObject = new JSONObject();
                //name需要转为字节数组
                byte[] name = people.getRealName().getBytes(StandardCharsets.UTF_8);
                String employeeNo = people.getEmployeeNo();
                JSONObject valid = new JSONObject();
                valid.put("beginTime", "2017-08-01T17:30:08");
                valid.put("enable", true);
                valid.put("endTime", "2030-08-01T17:30:08");

                JSONObject rightPlan = new JSONObject();
                rightPlan.put("doorNo", 1);
                rightPlan.put("planTemplateNo", "1,3,5");

                JSONArray rightPlanArray = new JSONArray();
                rightPlanArray.add(rightPlan);

                JSONObject userInfo = new JSONObject();
                userInfo.put("Valid", valid);
                userInfo.put("checkUser", false);
                userInfo.put("doorRight", "1");
                userInfo.put("RightPlan", rightPlanArray);
                userInfo.put("employeeNo", employeeNo);
                userInfo.put("floorNumber", 1);
                userInfo.put("maxOpenDoorTime", 0);
                userInfo.put("name", name);
                userInfo.put("openDelayEnabled", false);
                userInfo.put("password", "123456");
                userInfo.put("roomNumber", 1);
                userInfo.put("userType", "normal");

                JSONObject userInfoBuff = new JSONObject();
                userInfoBuff.put("UserInfo", userInfo);
                String userInfoBuffString = userInfoBuff.toJSONString();
                int iStringSize = userInfoBuffString.length();
                BYTE_ARRAY ptrByte = new BYTE_ARRAY(iStringSize);
                //将中文字符编码之后用数组拷贝的方式，避免因为编码导致的长度问题
                System.arraycopy(userInfoBuffString.getBytes(StandardCharsets.UTF_8), 0, ptrByte.byValue, 0, userInfoBuffString.length());
                ptrByte.write();
                log.info(new String(ptrByte.byValue));

                BYTE_ARRAY ptrOutuff = new BYTE_ARRAY(1024);

                IntByReference pInt = new IntByReference(0);
                int dwState = this.hikDevService.NET_DVR_SendWithRecvRemoteConfig(lHandler, ptrByte.getPointer(), iStringSize, ptrOutuff.getPointer(), 1024, pInt);
                //读取返回的json并解析
                ptrOutuff.read();
                String strResult = new String(ptrOutuff.byValue).trim();
                log.info("dwState:" + dwState + ",strResult:" + strResult);

                JSONObject jsonResult = JSONObject.parseObject(strResult);
                int statusCode = jsonResult.getIntValue("statusCode");
                String statusString = jsonResult.getString("statusString");

                if (dwState == -1 || dwState == HikConstant.NET_SDK_CONFIG_STATUS_FAILED || dwState == HikConstant.NET_SDK_CONFIG_STATUS_EXCEPTION) {
                    result.put("code", -1);
                    result.put("msg", "addMultiUserInfo NET_DVR_SendWithRecvRemoteConfig接口调用失败，错误码：" + this.hikDevService.NET_DVR_GetLastError());
                    jsonObject.put("employeeNo", employeeNo);
                    jsonObject.put("msg", "下发人员失败/异常, json return:" + jsonResult.toJSONString());
                    jsonArray.add(jsonObject);
                    log.info("addMultiUserInfo 下发人员失败/异常, json return:" + jsonResult.toJSONString());
                } else if (dwState == HikConstant.NET_SDK_CONFIG_STATUS_NEEDWAIT) {
                    log.info("配置等待");
                } else if (dwState == HikConstant.NET_SDK_CONFIG_STATUS_SUCCESS) {
                    //返回NET_SDK_CONFIG_STATUS_SUCCESS代表流程走通了，但并不代表下发成功，比如有些设备可能因为人员已存在等原因下发失败，所以需要解析Json报文
                    jsonObject.put("code", 0);
                    jsonObject.put("employeeNo", employeeNo);
                    if (statusCode != 1) {
                        jsonObject.put("msg", "下发人员成功,但是有异常情况:" + jsonResult.toJSONString());
                    } else {
                        jsonObject.put("msg", "下发人员成功:  json return:" + jsonResult.toJSONString());
                    }
                    jsonArray.add(jsonObject);
                } else if (dwState == HikConstant.NET_SDK_CONFIG_STATUS_FINISH) {
                    //下发人员时：dwState其实不会走到这里，因为设备不知道我们会下发多少个人，所以长连接需要我们主动关闭
                    log.info("下发人员完成");
                }
            }

            if (!this.hikDevService.NET_DVR_StopRemoteConfig(lHandler)) {
                log.info("NET_DVR_StopRemoteConfig接口调用失败，错误码：" + this.hikDevService.NET_DVR_GetLastError());
            } else {
                log.info("NET_DVR_StopRemoteConfig接口成功");
            }
        }
        if (!result.containsKey("code")) {
            result.put("code", 1);
            result.put("msg", "下发完成！");
            result.put("array", jsonArray);
        }
        return result;
    }

    @Override
    public JSONObject searchFaceInfo(String ip, AccessPeople people) {
        JSONObject result = new JSONObject();
        String strInBuffer = "POST /ISAPI/Intelligent/FDLib/FDSearch?format=json";
        Integer lHandler = this.startRemoteConfig(ip, strInBuffer);
        if (lHandler < 0) {
            result.put("code", -1);
            result.put("msg", "SearchFaceInfo NET_DVR_StartRemoteConfig 失败,错误码为" + this.hikDevService.NET_DVR_GetLastError());
            return result;
        } else {
            log.info("SearchFaceInfo NET_DVR_StartRemoteConfig成功!");

            JSONObject jsonObject = new JSONObject();
            jsonObject.put("searchResultPosition", 0);
            jsonObject.put("maxResults", 1);
            jsonObject.put("faceLibType", "blackFD");
            jsonObject.put("FDID", "1");
            //人脸关联的工号，同下发人员时的employeeNo字段
            jsonObject.put("FPID", people.getEmployeeNo());

            String strInbuff = jsonObject.toJSONString();
            log.info("查询人脸的json报文:" + strInbuff);

            //把string传递到Byte数组中，后续用.getPointer()方法传入指针地址中。
            BYTE_ARRAY ptrInbuff = new BYTE_ARRAY(strInbuff.length());
            System.arraycopy(strInbuff.getBytes(), 0, ptrInbuff.byValue, 0, strInbuff.length());
            ptrInbuff.write();

            NET_DVR_JSON_DATA_CFG m_struJsonData = new NET_DVR_JSON_DATA_CFG();
            m_struJsonData.write();

            IntByReference pInt = new IntByReference(0);

            while (true) {
                int dwState = this.hikDevService.NET_DVR_SendWithRecvRemoteConfig(lHandler, ptrInbuff.getPointer(), strInbuff.length(), m_struJsonData.getPointer(), m_struJsonData.size(), pInt);
                m_struJsonData.read();
                log.info(String.valueOf(dwState));
                if (dwState == -1 || dwState == HikConstant.NET_SDK_CONFIG_STATUS_FAILED || dwState == HikConstant.NET_SDK_CONFIG_STATUS_EXCEPTION) {
                    result.put("code", -1);
                    result.put("msg", "查询人脸失败/异常 NET_DVR_SendWithRecvRemoteConfig接口调用失败，错误码：" + this.hikDevService.NET_DVR_GetLastError());
                    break;
                } else if (dwState == HikConstant.NET_SDK_CONFIG_STATUS_NEEDWAIT) {
                    log.info("配置等待");
                } else if (dwState == HikConstant.NET_SDK_CONFIG_STATUS_SUCCESS) {
                    //解析JSON字符串
                    BYTE_ARRAY pJsonData = new BYTE_ARRAY(m_struJsonData.dwJsonDataSize);
                    pJsonData.write();
                    Pointer pPlateInfo = pJsonData.getPointer();
                    pPlateInfo.write(0, m_struJsonData.lpJsonData.getByteArray(0, pJsonData.size()), 0, pJsonData.size());
                    pJsonData.read();
                    String strResult = new String(pJsonData.byValue).trim();
                    System.out.println("strResult:" + strResult);
                    JSONObject jsonResult = JSONObject.parseObject(strResult);

                    int numOfMatches = jsonResult.getIntValue("numOfMatches");
                    //确认有人脸
                    // TODO 还没有返回信息
                    if (numOfMatches != 0) {
                        JSONArray MatchList = jsonResult.getJSONArray("MatchList");
                        JSONObject MatchList_1 = MatchList.getJSONObject(0);
                        //获取json中人脸关联的工号
                        String FPID = MatchList_1.getString("FPID");

                        FileOutputStream fout;
                        try {
                            fout = new FileOutputStream(".\\pic\\FPID_[" + FPID + "]_FacePic.jpg");
                            //将字节写入文件
                            long offset = 0;
                            ByteBuffer buffers = m_struJsonData.lpPicData.getByteBuffer(offset, m_struJsonData.dwPicDataSize);
                            byte[] bytes = new byte[m_struJsonData.dwPicDataSize];
                            buffers.rewind();
                            buffers.get(bytes);
                            fout.write(bytes);
                            fout.close();
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                    break;
                } else if (dwState == HikConstant.NET_SDK_CONFIG_STATUS_FINISH) {
                    log.info("获取人脸完成");
                    break;
                }
            }
            if (!this.hikDevService.NET_DVR_StopRemoteConfig(lHandler)) {
                result.put("code", -1);
                result.put("msg", "NET_DVR_StopRemoteConfig接口调用失败，错误码：" + this.hikDevService.NET_DVR_GetLastError());
                log.info("NET_DVR_StopRemoteConfig接口调用失败，错误码：" + this.hikDevService.NET_DVR_GetLastError());
            } else {
                log.info("NET_DVR_StopRemoteConfig接口成功");
            }

        }
        return result;
    }

    @Override
    public JSONObject addMultiFace(String ip, List<AccessPeople> peopleList) {
        JSONObject result = new JSONObject();
        JSONArray jsonArray = new JSONArray();
        // 获取用户句柄
        Integer longUserId = this.dataCache.getInteger(DataCachePrefixConstant.HIK_REG_USERID_IP + ip);
        if (null == longUserId) {
            result.put("code", -1);
            result.put("msg", "设备状态未注册！");
            return result;
        }
        //数组
        BYTE_ARRAY ptrByteArray = new BYTE_ARRAY(1024);
        String strInBuffer = "POST /ISAPI/Intelligent/FDLib/FaceDataRecord?format=json ";
        //字符串拷贝到数组中
        System.arraycopy(strInBuffer.getBytes(), 0, ptrByteArray.byValue, 0, strInBuffer.length());
        ptrByteArray.write();

        int lHandler = this.hikDevService.NET_DVR_StartRemoteConfig(longUserId, 2551, ptrByteArray.getPointer(), strInBuffer.length(), null, null);
        if (lHandler < 0) {
            result.put("code", -1);
            result.put("msg", "addMultiFace NET_DVR_StartRemoteConfig 失败,错误码为：" + this.hikDevService.NET_DVR_GetLastError());
            log.error("addMultiFace NET_DVR_StartRemoteConfig 失败,错误码为" + this.hikDevService.NET_DVR_GetLastError());
            return result;
        } else {
            log.info("addMultiFace NET_DVR_StartRemoteConfig 成功!");

            //批量下发多个人脸（不同工号）
            NET_DVR_JSON_DATA_CFG[] struAddFaceDataCfg = (NET_DVR_JSON_DATA_CFG[]) new NET_DVR_JSON_DATA_CFG().toArray(peopleList.size());

            for (AccessPeople people : peopleList) {
                int index = peopleList.indexOf(people);
                String employeeNo = people.getEmployeeNo();
                //下发的人脸图片
                String base64Pic = people.getBase64Pic();
                JSONObject singleJson = new JSONObject();
                struAddFaceDataCfg[index].read();

                JSONObject jsonObject = new JSONObject();
                jsonObject.put("faceLibType", "blackFD");
                jsonObject.put("FDID", "1");
                //人脸下发关联的工号
                jsonObject.put("FPID", employeeNo);

                String strJsonData = jsonObject.toString();
                System.out.println("下发人脸的json报文:" + strJsonData);

                //字符串拷贝到数组中
                System.arraycopy(strJsonData.getBytes(), 0, ptrByteArray.byValue, 0, strJsonData.length());
                ptrByteArray.write();

                struAddFaceDataCfg[index].dwSize = struAddFaceDataCfg[index].size();
                struAddFaceDataCfg[index].lpJsonData = ptrByteArray.getPointer();
                struAddFaceDataCfg[index].dwJsonDataSize = strJsonData.length();

                /*****************************************
                 * 从本地文件里面读取JPEG图片二进制数据
                 *****************************************/

                byte[] decode = Base64.decode(base64Pic);
                //转化为输入流
                ByteArrayInputStream picfile = new ByteArrayInputStream(decode);
                int picdataLength = picfile.available();
                if (picdataLength < 0) {
                    System.out.println("input file dataSize < 0");
                    result.put("code", -1);
                    result.put("msg", "input file dataSize < 0");
                    return result;
                }

                BYTE_ARRAY ptrpicByte = new BYTE_ARRAY(picdataLength);
                try {
                    picfile.read(ptrpicByte.byValue);
                    picfile.close();
                } catch (IOException e2) {
                    e2.printStackTrace();
                }
                ptrpicByte.write();
                struAddFaceDataCfg[index].dwPicDataSize = picdataLength;
                struAddFaceDataCfg[index].lpPicData = ptrpicByte.getPointer();
                struAddFaceDataCfg[index].write();

                BYTE_ARRAY ptrOutuff = new BYTE_ARRAY(1024);
                IntByReference pInt = new IntByReference(0);

                int dwState = this.hikDevService.NET_DVR_SendWithRecvRemoteConfig(lHandler, struAddFaceDataCfg[index].getPointer(), struAddFaceDataCfg[index].dwSize, ptrOutuff.getPointer(), ptrOutuff.size(), pInt);
                //读取返回的json并解析
                ptrOutuff.read();
                String strResult = new String(ptrOutuff.byValue).trim();
                log.info("dwState:" + dwState + ",strResult:" + strResult);

                if (strResult.isEmpty()) {
                    result.put("code", -1);
                    result.put("msg", "strResultIsEmpty");
                    return result;
                }
                JSONObject jsonResult = JSONObject.parseObject(strResult);
                int statusCode = jsonResult.getIntValue("statusCode");
                //String statusString = jsonResult.getString("statusString");

                if (dwState == -1 || dwState == HikConstant.NET_SDK_CONFIG_STATUS_FAILED || dwState == HikConstant.NET_SDK_CONFIG_STATUS_EXCEPTION) {
                    result.put("code", -1);
                    result.put("msg", "NET_DVR_SendWithRecvRemoteConfig接口调用失败，错误码：" + this.hikDevService.NET_DVR_GetLastError());
                    singleJson.put("strFPID", employeeNo);
                    singleJson.put("msg", "下发人脸失败/异常, json return:" + jsonResult.toJSONString());
                    log.error("下发人脸失败/异常, json return:" + jsonResult.toJSONString());
                    jsonArray.add(singleJson);
                } else if (dwState == HikConstant.NET_SDK_CONFIG_STATUS_SUCCESS) {
                    //返回NET_SDK_CONFIG_STATUS_SUCCESS代表流程走通了，但并不代表下发成功，比如人脸图片不符合设备规范等原因，所以需要解析Json报文
                    singleJson.put("code", 0);
                    singleJson.put("strFPID", employeeNo);
                    singleJson.put("msg", "下发人脸成功,但是有异常情况, json return:" + jsonResult.toJSONString());
                    if (statusCode != 1) {
                        log.info("下发人脸成功,但是有异常情况:" + jsonResult.toJSONString());
                    } else {
                        log.info("下发人脸成功,  json return:" + jsonResult.toJSONString());
                    }
                    jsonArray.add(singleJson);
                    //可以继续下发下一个
                } else if (dwState == HikConstant.NET_SDK_CONFIG_STATUS_FINISH) {
                    //下发人脸时：dwState其实不会走到这里，因为设备不知道我们会下发多少个人，所以长连接需要我们主动关闭
                    log.info("下发人脸完成");
                } else {
                    log.info("下发人脸识别，其他状态：" + dwState);
                }
            }

            if (!this.hikDevService.NET_DVR_StopRemoteConfig(lHandler)) {
                result.put("code", -1);
                result.put("msg", "NET_DVR_StopRemoteConfig接口调用失败，错误码：" + this.hikDevService.NET_DVR_GetLastError());
                log.error("NET_DVR_StopRemoteConfig接口调用失败，错误码：" + this.hikDevService.NET_DVR_GetLastError());
            } else {
                log.info("NET_DVR_StopRemoteConfig接口成功");
            }
        }
        if (!result.containsKey("code")) {
            result.put("code", 1);
            result.put("msg", "下发完成");
            result.put("data", jsonArray);
        }
        return result;
    }

    @Override
    public JSONObject delFaceInfo(String ip, String[] employeeIds) {
        JSONObject result = new JSONObject();
        JSONArray array = new JSONArray();
        for (String id : employeeIds) {
            JSONObject valueObj = new JSONObject();
            valueObj.put("value", id);
            array.add(valueObj);
        }
        JSONObject fpid = new JSONObject();
        fpid.put("FPID", array);
        // 获取用户句柄
        Integer longUserId = this.dataCache.getInteger(DataCachePrefixConstant.HIK_REG_USERID_IP + ip);
        if (null == longUserId) {
            result.put("code", -1);
            result.put("msg", "设备状态未注册！");
            return result;
        }
        String strURL = "PUT /ISAPI/Intelligent/FDLib/FDSearch/Delete?format=json&FDID=1&faceLibType=blackFD";
        BYTE_ARRAY ptrUrl = new BYTE_ARRAY(HikConstant.BYTE_ARRAY_LEN);
        System.arraycopy(strURL.getBytes(), 0, ptrUrl.byValue, 0, strURL.length());
        ptrUrl.write();
        //输入删除条件
        BYTE_ARRAY ptrInBuffer = new BYTE_ARRAY(HikConstant.ISAPI_DATA_LEN);
        ptrInBuffer.read();
        String strInbuffer = fpid.toJSONString();
        log.info("打印json封装：{}", strInbuffer);
        ptrInBuffer.byValue = strInbuffer.getBytes();
        ptrInBuffer.write();
        NET_DVR_XML_CONFIG_INPUT struXMLInput = new NET_DVR_XML_CONFIG_INPUT();
        struXMLInput.read();
        struXMLInput.dwSize = struXMLInput.size();
        struXMLInput.lpRequestUrl = ptrUrl.getPointer();
        struXMLInput.dwRequestUrlLen = ptrUrl.byValue.length;
        struXMLInput.lpInBuffer = ptrInBuffer.getPointer();
        struXMLInput.dwInBufferSize = ptrInBuffer.byValue.length;
        struXMLInput.write();
        BYTE_ARRAY ptrStatusByte = new BYTE_ARRAY(HikConstant.ISAPI_STATUS_LEN);
        ptrStatusByte.read();
        BYTE_ARRAY ptrOutByte = new BYTE_ARRAY(HikConstant.ISAPI_DATA_LEN);
        ptrOutByte.read();
        NET_DVR_XML_CONFIG_OUTPUT struXMLOutput = new NET_DVR_XML_CONFIG_OUTPUT();
        struXMLOutput.read();
        struXMLOutput.dwSize = struXMLOutput.size();
        struXMLOutput.lpOutBuffer = ptrOutByte.getPointer();
        struXMLOutput.dwOutBufferSize = ptrOutByte.size();
        struXMLOutput.lpStatusBuffer = ptrStatusByte.getPointer();
        struXMLOutput.dwStatusSize = ptrStatusByte.size();
        struXMLOutput.write();
        if (!this.hikDevService.NET_DVR_STDXMLConfig(longUserId, struXMLInput, struXMLOutput)) {
            int iErr = this.hikDevService.NET_DVR_GetLastError();
            result.put("code", -1);
            result.put("msg", "NET_DVR_STDXMLConfig失败，错误号：" + iErr);
            return result;
        } else {
            struXMLOutput.read();
            ptrOutByte.read();
            ptrStatusByte.read();
            String strOutXML = new String(ptrOutByte.byValue).trim();
            log.info("删除人脸输出结果:" + strOutXML);
            String strStatus = new String(ptrStatusByte.byValue).trim();
            log.info("删除人脸返回状态：" + strStatus);
            result.put("code", 1);
            result.put("msg", "删除人脸成功");
            result.put("data", strOutXML);
        }
        return result;
    }

    @Override
    public JSONObject delUserInfo(String ip, String[] employeeIds) {
        JSONObject result = new JSONObject();
        JSONArray array = new JSONArray();
        for (String employeeId : employeeIds) {
            JSONObject employeeNo = new JSONObject();
            employeeNo.put("employeeNo", employeeId);
            array.add(employeeNo);
        }
        JSONObject employeeNoList = new JSONObject();
        employeeNoList.put("EmployeeNoList", array);
        JSONObject userInfoDelCond = new JSONObject();
        userInfoDelCond.put("UserInfoDelCond", employeeNoList);
        // 获取用户句柄
        Integer longUserId = this.dataCache.getInteger(DataCachePrefixConstant.HIK_REG_USERID_IP + ip);
        if (null == longUserId) {
            result.put("code", -1);
            result.put("msg", "设备状态未注册！");
            return result;
        }
        String strURL = "PUT /ISAPI/AccessControl/UserInfo/Delete?format=json";
        BYTE_ARRAY ptrUrl = new BYTE_ARRAY(HikConstant.BYTE_ARRAY_LEN);
        System.arraycopy(strURL.getBytes(), 0, ptrUrl.byValue, 0, strURL.length());
        ptrUrl.write();
        //输入删除条件
        BYTE_ARRAY ptrInBuffer = new BYTE_ARRAY(HikConstant.ISAPI_DATA_LEN);
        ptrInBuffer.read();
        String strInbuffer = userInfoDelCond.toJSONString();
        log.info("打印json封装：{}", strInbuffer);
        ptrInBuffer.byValue = strInbuffer.getBytes();
        ptrInBuffer.write();
        NET_DVR_XML_CONFIG_INPUT struXMLInput = new NET_DVR_XML_CONFIG_INPUT();
        struXMLInput.read();
        struXMLInput.dwSize = struXMLInput.size();
        struXMLInput.lpRequestUrl = ptrUrl.getPointer();
        struXMLInput.dwRequestUrlLen = ptrUrl.byValue.length;
        struXMLInput.lpInBuffer = ptrInBuffer.getPointer();
        struXMLInput.dwInBufferSize = ptrInBuffer.byValue.length;
        struXMLInput.write();
        BYTE_ARRAY ptrStatusByte = new BYTE_ARRAY(HikConstant.ISAPI_STATUS_LEN);
        ptrStatusByte.read();
        BYTE_ARRAY ptrOutByte = new BYTE_ARRAY(HikConstant.ISAPI_DATA_LEN);
        ptrOutByte.read();
        NET_DVR_XML_CONFIG_OUTPUT struXMLOutput = new NET_DVR_XML_CONFIG_OUTPUT();
        struXMLOutput.read();
        struXMLOutput.dwSize = struXMLOutput.size();
        struXMLOutput.lpOutBuffer = ptrOutByte.getPointer();
        struXMLOutput.dwOutBufferSize = ptrOutByte.size();
        struXMLOutput.lpStatusBuffer = ptrStatusByte.getPointer();
        struXMLOutput.dwStatusSize = ptrStatusByte.size();
        struXMLOutput.write();

        if (!this.hikDevService.NET_DVR_STDXMLConfig(longUserId, struXMLInput, struXMLOutput)) {
            int iErr = this.hikDevService.NET_DVR_GetLastError();
            result.put("code", -1);
            result.put("msg", "NET_DVR_STDXMLConfig失败，错误号：" + iErr);
            log.error("NET_DVR_STDXMLConfig失败，错误号：" + iErr);
            return result;
        } else {
            struXMLOutput.read();
            ptrOutByte.read();
            ptrStatusByte.read();
            String strOutXML = new String(ptrOutByte.byValue).trim();
            log.info("删除人员输出结果:" + strOutXML);
            String strStatus = new String(ptrStatusByte.byValue).trim();
            log.info("删除人员返回状态：" + strStatus);
            result.put("code", 1);
            result.put("msg", "删除人员成功");
            result.put("data", strOutXML);
        }
        return result;
    }

    private Integer startRemoteConfig(String ip, String strInBuffer) {
        // 获取用户句柄
        Integer longUserId = this.dataCache.getInteger(DataCachePrefixConstant.HIK_REG_USERID_IP + ip);
        if (null != longUserId && longUserId != -1) {
            //  数组
            BYTE_ARRAY ptrByteArray = new BYTE_ARRAY(1024);
            //字符串拷贝到数组中
            System.arraycopy(strInBuffer.getBytes(), 0, ptrByteArray.byValue, 0, strInBuffer.length());
            ptrByteArray.write();

            return this.hikDevService.NET_DVR_StartRemoteConfig(longUserId, 2550, ptrByteArray.getPointer(), strInBuffer.length(), null, null);
        }
        return -1;
    }
}