package com.bookplus.ci

/** Lista central de microservicios Maven del monorepo BookPlus. */
class Services {
    static final List<String> ALL = [
        'book-plus-auth-service',
        'book-plus-catalog-service',
        'book-plus-cart-service',
        'book-plus-order-service',
        'book-plus-payment-service',
        'book-plus-inventory-service',
        'book-plus-notification-service',
        'book-plus-report-service',
        'book-plus-api-gateway',
    ]
}
