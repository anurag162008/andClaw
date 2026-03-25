using System.IO.Compression;

namespace AndClaw.Windows.Services;

public sealed class TransferService
{
    public string ExportSettings(string sourceDirectory, string destinationDirectory)
    {
        Directory.CreateDirectory(destinationDirectory);
        var artifactName = $"andclaw-transfer-{DateTime.UtcNow:yyyyMMdd-HHmmss}.zip";
        var destinationArtifact = Path.Combine(destinationDirectory, artifactName);

        var tempArtifact = Path.Combine(Path.GetTempPath(), artifactName);
        if (File.Exists(tempArtifact))
        {
            File.Delete(tempArtifact);
        }

        ZipFile.CreateFromDirectory(sourceDirectory, tempArtifact);
        File.Copy(tempArtifact, destinationArtifact, overwrite: true);
        File.Delete(tempArtifact);

        return destinationArtifact;
    }

    public void ImportSettings(string artifactPath, string destinationDirectory)
    {
        Directory.CreateDirectory(destinationDirectory);
        ZipFile.ExtractToDirectory(artifactPath, destinationDirectory, overwriteFiles: true);
    }
}
