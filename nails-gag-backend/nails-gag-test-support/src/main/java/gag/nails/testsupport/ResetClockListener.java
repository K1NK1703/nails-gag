package gag.nails.testsupport;

import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestExecutionListener;

/**
 * Возвращает общие {@link MutableClock} на {@link TestTimeConfiguration#START} перед каждым тестом:
 * контекст Spring кэшируется между тестами, и сдвиг часов в одном тесте иначе дошёл бы до следующего.
 */
public final class ResetClockListener implements TestExecutionListener {

    @Override
    public void beforeTestMethod(TestContext testContext) {
        testContext.getApplicationContext().getBean(MutableClock.class).set(TestTimeConfiguration.START);
    }
}
