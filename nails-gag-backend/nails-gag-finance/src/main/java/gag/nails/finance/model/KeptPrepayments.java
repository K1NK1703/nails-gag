package gag.nails.finance.model;

import java.math.BigDecimal;

/**
 * Удержанные предоплаты периода (по дню отмены или неявки).
 *
 * @param count число удержанных предоплат
 * @param amount их сумма
 */
public record KeptPrepayments(int count, BigDecimal amount) {}
