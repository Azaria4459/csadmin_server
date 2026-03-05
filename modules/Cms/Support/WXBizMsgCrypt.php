<?php
declare(strict_types=1);

namespace Modules\Cms\Support;

/**
 * 企业微信消息加解密类
 * 参考文档: https://developer.work.weixin.qq.com/document/path/90968
 */
class WXBizMsgCrypt
{
    private $token;
    private $encodingAesKey;
    private $corpId;

    /**
     * 构造函数
     * @param string $token 企业微信后台配置的Token
     * @param string $encodingAesKey 企业微信后台配置的EncodingAESKey
     * @param string $corpId 企业ID
     */
    public function __construct($token, $encodingAesKey, $corpId)
    {
        $this->token = $token;
        $this->encodingAesKey = $encodingAesKey;
        $this->corpId = $corpId;
    }

    /**
     * 验证URL
     * @param string $msgSignature 签名
     * @param string $timestamp 时间戳
     * @param string $nonce 随机数
     * @param string $echostr 加密的随机字符串
     * @return array [错误码, 解密后的echostr]
     */
    public function verifyUrl($msgSignature, $timestamp, $nonce, $echostr)
    {
        if (strlen($this->encodingAesKey) != 43) {
            return [ErrorCode::$IllegalAesKey, null];
        }

        // 验证签名
        $signature = $this->getSHA1($this->token, $timestamp, $nonce, $echostr);
        if ($signature != $msgSignature) {
            return [ErrorCode::$ValidateSignatureError, null];
        }

        // 解密
        $result = $this->decrypt($echostr);
        return $result;
    }

    /**
     * 解密消息
     * @param string $encryptMsg 加密的消息
     * @return array [错误码, 解密后的消息]
     */
    private function decrypt($encryptMsg)
    {
        try {
            // base64解码
            $ciphertext = base64_decode($encryptMsg);
            
            // 获取AES密钥
            $aesKey = base64_decode($this->encodingAesKey . '=');
            
            // 使用AES-256-CBC解密
            $decrypted = openssl_decrypt(
                $ciphertext,
                'AES-256-CBC',
                $aesKey,
                OPENSSL_RAW_DATA | OPENSSL_ZERO_PADDING,
                substr($aesKey, 0, 16)
            );

            if ($decrypted === false) {
                return [ErrorCode::$DecryptAESError, null];
            }

            // 去除补位字符
            $pad = ord(substr($decrypted, -1));
            if ($pad < 1 || $pad > 32) {
                $pad = 0;
            }
            $decrypted = substr($decrypted, 0, strlen($decrypted) - $pad);

            // 解析内容：16字节随机字符串 + 4字节消息长度 + 消息内容 + corpId
            $content = substr($decrypted, 16);
            $msgLen = unpack('N', substr($content, 0, 4))[1];
            $msg = substr($content, 4, $msgLen);
            $fromCorpId = substr($content, 4 + $msgLen);

            // 验证corpId
            if ($fromCorpId != $this->corpId) {
                return [ErrorCode::$ValidateCorpidError, null];
            }

            return [0, $msg];

        } catch (\Exception $e) {
            return [ErrorCode::$DecryptAESError, null];
        }
    }

    /**
     * 计算签名
     */
    private function getSHA1($token, $timestamp, $nonce, $encryptMsg)
    {
        $array = [$token, $timestamp, $nonce, $encryptMsg];
        sort($array, SORT_STRING);
        return sha1(implode($array));
    }

    /**
     * 解密POST消息体
     * @param string $msgSignature 签名
     * @param string $timestamp 时间戳
     * @param string $nonce 随机数
     * @param string $postData POST的XML数据
     * @return array [错误码, 解密后的消息XML]
     */
    public function decryptMsg($msgSignature, $timestamp, $nonce, $postData)
    {
        if (strlen($this->encodingAesKey) != 43) {
            return [ErrorCode::$IllegalAesKey, null];
        }

        // 提取加密消息
        $xml = simplexml_load_string($postData, 'SimpleXMLElement', LIBXML_NOCDATA);
        if (!$xml) {
            return [ErrorCode::$ParseXmlError, null];
        }

        $encryptMsg = (string)$xml->Encrypt;

        // 验证签名
        $signature = $this->getSHA1($this->token, $timestamp, $nonce, $encryptMsg);
        if ($signature != $msgSignature) {
            return [ErrorCode::$ValidateSignatureError, null];
        }

        // 解密消息
        $result = $this->decrypt($encryptMsg);
        return $result;
    }
}

/**
 * 错误码
 */
class ErrorCode
{
    public static $OK = 0;
    public static $ValidateSignatureError = -40001;
    public static $ParseXmlError = -40002;
    public static $ComputeSignatureError = -40003;
    public static $IllegalAesKey = -40004;
    public static $ValidateCorpidError = -40005;
    public static $EncryptAESError = -40006;
    public static $DecryptAESError = -40007;
    public static $IllegalBuffer = -40008;
}

