package com.lowaltitude.inspection.trade.application;

import java.util.Optional;

/**
 * 售后凭证图片的对象存储端口（S4-2）。开发环境由启动模块接本机目录（trade.aftersale.image-dir）；真实环境接私有桶，
 * 另写一个实现，不改交易模块。存储键由交易模块生成（aftersale/UUID.扩展名）；图片不公开，只经受权接口回读。
 */
public interface TradeAftersaleImageStore {

    void put(String key, byte[] content, String contentType);

    Optional<byte[]> read(String key);

    /** 没配置存储时用：上传回 503，读不到。 */
    static TradeAftersaleImageStore unavailable() {
        return new TradeAftersaleImageStore() {
            @Override
            public void put(String key, byte[] content, String contentType) {
                throw new UnavailableException("aftersale image storage is not configured", null);
            }

            @Override
            public Optional<byte[]> read(String key) {
                return Optional.empty();
            }
        };
    }

    final class UnavailableException extends RuntimeException {
        public UnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
