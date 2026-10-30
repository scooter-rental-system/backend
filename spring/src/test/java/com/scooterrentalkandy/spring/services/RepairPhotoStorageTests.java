package com.scooterrentalkandy.spring.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import com.scooterrentalkandy.spring.exception.ApiException;
import com.scooterrentalkandy.spring.services.RepairPhotoValidator.ImageType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Local repair photo storage: generated keys only, inside its directory, never overwriting. */
class RepairPhotoStorageTests {

    @TempDir
    Path dir;

    @Test
    void storesReadsAndDeletesByGeneratedKey() throws Exception {
        LocalRepairPhotoStorage storage = new LocalRepairPhotoStorage(dir.resolve("photos").toString());
        String key = RepairPhotoValidator.newKey(ImageType.PNG);

        storage.save(key, new byte[] {1, 2, 3});

        assertThat(storage.read(key)).containsExactly(1, 2, 3);
        assertThat(Files.list(dir.resolve("photos")).map(p -> p.getFileName().toString())).containsExactly(key);
        storage.delete(key);
        assertThatThrownBy(() -> storage.read(key)).isInstanceOf(ApiException.class);
    }

    @Test
    void neverOverwritesAnExistingPhoto() {
        LocalRepairPhotoStorage storage = new LocalRepairPhotoStorage(dir.toString());
        String key = RepairPhotoValidator.newKey(ImageType.JPEG);
        storage.save(key, new byte[] {1});

        assertThatThrownBy(() -> storage.save(key, new byte[] {2})).isInstanceOf(IllegalStateException.class);
        assertThat(storage.read(key)).containsExactly(1);
    }

    @Test
    void refusesAnythingButAGeneratedKey() {
        LocalRepairPhotoStorage storage = new LocalRepairPhotoStorage(dir.resolve("photos").toString());
        for (String key : new String[] {"../secret.png", "..\\secret.png", "/etc/passwd", "C:\\x.png", "photo.png",
                "00000000-0000-0000-0000-000000000000.svg", "00000000-0000-0000-0000-000000000000.png/../x.png", "",
                null}) {
            assertThatThrownBy(() -> storage.save(key, new byte[] {1})).as(String.valueOf(key))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> storage.read(key)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(Files.exists(dir.resolve("secret.png"))).isFalse();
    }

    @Test
    void generatedKeysAreRandomAndCarryOnlyTheCheckedType() {
        String a = RepairPhotoValidator.newKey(ImageType.WEBP);
        String b = RepairPhotoValidator.newKey(ImageType.WEBP);
        assertThat(a).isNotEqualTo(b).endsWith(".webp");
        assertThat(RepairPhotoValidator.isKey(a)).isTrue();
        assertThat(RepairPhotoValidator.newKey(ImageType.JPEG)).endsWith(".jpg");
    }
}
