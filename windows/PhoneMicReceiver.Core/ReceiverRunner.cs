namespace PhoneMicReceiver.Core;

public sealed class ReceiverRunner
{
    public async Task RunUntilCancelledAsync(ReceiverConfig config, CancellationToken cancellationToken)
    {
        await using var engine = new ReceiverEngine();
        engine.OnLog += Console.WriteLine;
        engine.OnStateChanged += state => Console.WriteLine($"[state] {state}");

        await engine.StartAsync(config, cancellationToken);

        try
        {
            await Task.Delay(Timeout.InfiniteTimeSpan, cancellationToken);
        }
        catch (OperationCanceledException)
        {
            // expected on shutdown
        }

        await engine.StopAsync();
    }
}
